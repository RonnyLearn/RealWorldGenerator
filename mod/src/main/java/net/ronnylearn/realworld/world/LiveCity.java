package net.ronnylearn.realworld.world;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.ronnylearn.realworld.arnis.ArnisLibrary;
import net.ronnylearn.realworld.geo.GeoBox;

import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * The city as it is being built, for the generation screen: a grid over the selected area
 * that fills with the real top blocks and heights as Arnis writes them (read live from its
 * preview accumulator) and with every finished piece, drawn the same way as the finished
 * world. Cells nothing has been built on yet show the street map lying flat.
 */
public final class LiveCity {
	/** Long side of the grid before the world's block frame is known (the map only). */
	private static final int GHOST_GRID = 240;
	/** Long side of the grid once it is; about what the final picture shows. */
	private static final int GRID = 760;
	/** Long side of the grid for the finished city, which can be zoomed into. */
	private static final int FINE_GRID = 2000;
	private static final int FINE_WIDTH = 3200;
	private static final double EARTH_RADIUS = 6_371_000.0;

	private final GeoBox box;
	private final Path world;
	private final int[] map;
	private final int mapW, mapH;

	private int cols, rows;
	private boolean framed;
	/** Block x/z of the area's north-west corner and blocks per cell, once framed. */
	private double x0, z0, x1, z1, cell = 1;
	private int[] colors;
	private short[] heights;
	private int[] stamp;
	private int pass;
	private long lastVersion = Long.MIN_VALUE;
	private Arena arena;
	private MemorySegment liveColors, liveHeights;
	private long liveLength;

	public LiveCity(GeoBox box, NativeImage snapshot, Path world) {
		this.box = box;
		this.world = world;
		if (snapshot != null) {
			int step = Math.max(1, Math.max(snapshot.getWidth(), snapshot.getHeight()) / 512);
			mapW = snapshot.getWidth() / step;
			mapH = snapshot.getHeight() / step;
			map = new int[mapW * mapH];
			for (int y = 0; y < mapH; y++) {
				for (int x = 0; x < mapW; x++) {
					map[y * mapW + x] = snapshot.getPixel(x * step, y * step);
				}
			}
		} else {
			map = null;
			mapW = mapH = 0;
		}
		grid(GHOST_GRID);
	}

	private void grid(int longSide) {
		double w = Math.max(1, box.widthMeters()), h = Math.max(1, box.heightMeters());
		if (w >= h) {
			cols = longSide;
			rows = Math.max(1, (int) Math.round(longSide * h / w));
		} else {
			rows = longSide;
			cols = Math.max(1, (int) Math.round(longSide * w / h));
		}
		allocate();
	}

	private void allocate() {
		colors = new int[cols * rows];
		heights = new short[cols * rows];
		stamp = new int[cols * rows];
		Arrays.fill(heights, Short.MIN_VALUE);
	}

	/**
	 * Ties the grid to the world's blocks: Arnis pins a One World to a Web Mercator frame,
	 * written to its manifest as soon as the world exists.
	 */
	private boolean frame() {
		if (framed) {
			return true;
		}
		Path manifest = world.resolve("arnis_one_world.json");
		if (!Files.isRegularFile(manifest)) {
			return false;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
			double originLat = root.get("origin_lat").getAsDouble();
			double originLon = root.get("origin_lon").getAsDouble();
			double scale = root.get("scale").getAsDouble();
			double k = scale * Math.cos(Math.toRadians(originLat));
			double originY = mercatorY(originLat);
			x0 = EARTH_RADIUS * Math.toRadians(box.minLng() - originLon) * k;
			x1 = EARTH_RADIUS * Math.toRadians(box.maxLng() - originLon) * k;
			z0 = -(mercatorY(box.maxLat()) - originY) * k;
			z1 = -(mercatorY(box.minLat()) - originY) * k;
			resize(GRID);
			framed = true;
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	/** A grid of about {@code longSide} cells over the framed area. */
	private void resize(int longSide) {
		double bw = x1 - x0, bh = z1 - z0;
		cell = Math.max(1, Math.max(bw, bh) / longSide);
		cols = Math.max(1, (int) Math.ceil(bw / cell));
		rows = Math.max(1, (int) Math.ceil(bh / cell));
		allocate();
	}

	/**
	 * The finished city in detail, for zooming in: the grid is rebuilt finer from the saved
	 * pieces (the live data of the last moments is in them too) and drawn larger.
	 */
	public synchronized IsoRender.Raster renderDetailed(java.util.List<WorldPicture.Area> areas) {
		if (!frame()) {
			return render();
		}
		resize(FINE_GRID);
		for (WorldPicture.Area a : areas) {
			addPreviewLocked(a);
		}
		return render(FINE_WIDTH);
	}

	private static double mercatorY(double lat) {
		return EARTH_RADIUS * Math.log(Math.tan(Math.PI / 4 + Math.toRadians(lat) / 2));
	}

	/** Takes what Arnis has built so far in the piece it is on; true when something new arrived. */
	public synchronized boolean pollLive(ArnisLibrary arnis) {
		if (!frame()) {
			return false;
		}
		long[] info = arnis.liveInfo();
		if (info == null || info[7] == lastVersion) {
			return false;
		}
		int w = (int) info[0], h = (int) info[1];
		long n = (long) w * h;
		if (n <= 0) {
			return false;
		}
		if (n != liveLength) {
			if (arena != null) {
				arena.close();
			}
			arena = Arena.ofShared();
			liveColors = arena.allocate(ValueLayout.JAVA_INT, n);
			liveHeights = arena.allocate(ValueLayout.JAVA_SHORT, n);
			liveLength = n;
		}
		if (!arnis.liveCopy(liveColors, liveHeights, n)) {
			return false;
		}
		lastVersion = info[7];
		place(liveColors.toArray(ValueLayout.JAVA_INT), liveHeights.toArray(ValueLayout.JAVA_SHORT), w, h,
			(int) info[2], (int) info[3], (int) info[4]);
		return true;
	}

	/** Adds a finished piece from the preview Arnis saved for it (the live data is gone by then). */
	public synchronized void addPreview(WorldPicture.Area area) {
		addPreviewLocked(area);
	}

	private void addPreviewLocked(WorldPicture.Area area) {
		if (!frame() || area.preview() == null || !Files.isRegularFile(area.preview())) {
			return;
		}
		try {
			ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(IsoRender.heightsOf(area.preview()))).order(ByteOrder.LITTLE_ENDIAN);
			byte[] magic = new byte[4];
			buf.get(magic);
			int w = buf.getInt(), h = buf.getInt(), step = Math.max(1, buf.getInt());
			short[] hs = new short[w * h];
			buf.asShortBuffer().get(hs);
			int[] cs = new int[w * h];
			try (NativeImage image = NativeImage.read(Files.readAllBytes(area.preview()))) {
				if (image.getWidth() != w || image.getHeight() != h) {
					return;
				}
				for (int y = 0; y < h; y++) {
					for (int x = 0; x < w; x++) {
						cs[y * w + x] = image.getPixel(x, y);
					}
				}
			}
			place(cs, hs, w, h, step, area.minX(), area.minZ());
		} catch (IOException ignored) {
		}
	}

	/**
	 * Lays pixels of {@code step} blocks starting at block (minX, minZ) onto the grid. The newest
	 * data wins over what a cell held before; within one pass the tallest column does.
	 */
	private void place(int[] cs, short[] hs, int w, int h, int step, int minX, int minZ) {
		pass++;
		for (int pz = 0; pz < h; pz++) {
			double bz = minZ + (double) pz * step - z0;
			int cz0 = (int) Math.floor(bz / cell), cz1 = (int) Math.floor((bz + step - 1e-6) / cell);
			if (cz1 < 0 || cz0 >= rows) {
				continue;
			}
			for (int px = 0; px < w; px++) {
				short y = hs[pz * w + px];
				if (y == Short.MIN_VALUE) {
					continue;
				}
				double bx = minX + (double) px * step - x0;
				int cx0 = (int) Math.floor(bx / cell), cx1 = (int) Math.floor((bx + step - 1e-6) / cell);
				if (cx1 < 0 || cx0 >= cols) {
					continue;
				}
				int color = cs[pz * w + px];
				for (int cz = Math.max(0, cz0); cz <= Math.min(rows - 1, cz1); cz++) {
					for (int cx = Math.max(0, cx0); cx <= Math.min(cols - 1, cx1); cx++) {
						int i = cz * cols + cx;
						if (stamp[i] != pass || y > heights[i]) {
							stamp[i] = pass;
							heights[i] = y;
							colors[i] = color;
						}
					}
				}
			}
		}
	}

	/** The city as it stands: what is built in its real colours and heights, the rest as the street map lying flat. */
	public synchronized IsoRender.Raster render() {
		return render(1500);
	}

	private IsoRender.Raster render(int maxWidth) {
		int n = cols * rows;
		int ground = Integer.MAX_VALUE;
		for (short y : heights) {
			if (y != Short.MIN_VALUE && y < ground) {
				ground = y;
			}
		}
		if (ground == Integer.MAX_VALUE) {
			ground = 0;
		}
		int[] c = new int[n];
		short[] h = new short[n];
		for (int j = 0; j < rows; j++) {
			for (int i = 0; i < cols; i++) {
				int idx = j * cols + i;
				if (heights[idx] != Short.MIN_VALUE) {
					c[idx] = colors[idx];
					h[idx] = heights[idx];
				} else {
					c[idx] = ghost(i, j);
					h[idx] = (short) ground;
				}
			}
		}
		return IsoRender.rasterise(c, h, cols, rows, cell, maxWidth);
	}

	/** The street map under a cell, dimmed so the real blocks stand out against it. */
	private int ghost(int i, int j) {
		if (map == null) {
			return 0xFF36302B;
		}
		int x = Math.min(mapW - 1, (int) ((i + 0.5) / cols * mapW));
		int y = Math.min(mapH - 1, (int) ((j + 0.5) / rows * mapH));
		int c = map[y * mapW + x];
		// ABGR; the same weight on every channel, so the order does not matter here.
		int r = ((c & 0xFF) * 55 + 0x2A * 45) / 100;
		int g = (((c >> 8) & 0xFF) * 55 + 0x30 * 45) / 100;
		int b = (((c >> 16) & 0xFF) * 55 + 0x36 * 45) / 100;
		return 0xFF000000 | (b << 16) | (g << 8) | r;
	}

	/** Frees the buffers the live data is copied through. */
	public synchronized void close() {
		if (arena != null) {
			arena.close();
			arena = null;
			liveLength = 0;
		}
	}
}
