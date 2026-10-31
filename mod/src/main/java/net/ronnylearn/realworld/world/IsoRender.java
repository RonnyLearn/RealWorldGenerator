package net.ronnylearn.realworld.world;

import com.mojang.blaze3d.platform.NativeImage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The generated area as an isometric picture, block for block: Arnis's top-down colours
 * (one pixel per column) and the matching top-block heights become columns with a lit top
 * and two shaded sides. Everything is rasterised once, on a worker thread, into a single
 * image, so showing the exact city costs one textured quad per frame.
 */
public final class IsoRender {
	/** Widest picture produced; bigger areas are drawn with several blocks per column. */
	private static final int MAX_WIDTH = 1500;
	private static final int MAX_TILE = 6;

	private IsoRender() {
	}

	/** Largest grid the previews are combined into before rasterising (columns per side). */
	private static final int MAX_GRID = 3000;

	public static Path heightsOf(Path png) {
		String name = png.getFileName().toString();
		return png.resolveSibling(name.substring(0, name.length() - 4) + ".heights");
	}

	/**
	 * Renders the given areas (Arnis previews with their heights) together, each at its real
	 * place, so a city built in pieces shows as one. Null when none has a preview; the caller
	 * owns the returned image.
	 */
	public static NativeImage render(List<WorldPicture.Area> areas) throws IOException {
		List<WorldPicture.Area> usable = areas.stream()
			.filter(a -> a.preview() != null && Files.isRegularFile(a.preview()) && Files.isRegularFile(heightsOf(a.preview())))
			.toList();
		int[] b = WorldPicture.bounds(usable);
		if (b == null) {
			return null;
		}
		// Never finer than the coarsest preview, or its pixels would land with gaps between them.
		int step = Math.max(1, (int) Math.ceil(Math.max(b[2] - b[0] + 1, b[3] - b[1] + 1) / (double) MAX_GRID));
		for (WorldPicture.Area a : usable) {
			step = Math.max(step, previewStep(a.preview()));
		}
		int w = (b[2] - b[0]) / step + 1, h = (b[3] - b[1]) / step + 1;
		int[] colors = new int[w * h];
		short[] heights = new short[w * h];
		java.util.Arrays.fill(heights, Short.MIN_VALUE);
		for (WorldPicture.Area a : usable) {
			place(a, b, step, w, h, colors, heights);
		}
		return rasterise(colors, heights, w, h, step).image();
	}

	/**
	 * An isometric picture and where its ground plane lies in it: the plane's top corner is
	 * {@code planeTop} pixels down, and the plane is {@code planeWidth} by {@code planeHeight}.
	 * Pictures of the same grid line up by their plane, whatever stands on it.
	 */
	public record Raster(NativeImage image, int planeTop, int planeWidth, int planeHeight) {
	}

	/** Blocks per pixel of a preview, from its heights file header. */
	private static int previewStep(Path png) throws IOException {
		try (var in = Files.newInputStream(heightsOf(png))) {
			byte[] header = in.readNBytes(16);
			if (header.length < 16) {
				throw new IOException("Short heights file");
			}
			return Math.max(1, ByteBuffer.wrap(header, 12, 4).order(ByteOrder.LITTLE_ENDIAN).getInt());
		}
	}

	/** Copies one area's preview into the combined grid, keeping the tallest column per cell. */
	private static void place(WorldPicture.Area a, int[] b, int step, int w, int h, int[] colors, short[] heights) throws IOException {
		ByteBuffer buf = ByteBuffer.wrap(Files.readAllBytes(heightsOf(a.preview()))).order(ByteOrder.LITTLE_ENDIAN);
		byte[] magic = new byte[4];
		buf.get(magic);
		if (!"RWH1".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
			throw new IOException("Not a heights file");
		}
		int pw = buf.getInt(), ph = buf.getInt(), pstep = Math.max(1, buf.getInt());
		short[] ph16 = new short[pw * ph];
		buf.asShortBuffer().get(ph16);
		try (NativeImage image = NativeImage.read(Files.readAllBytes(a.preview()))) {
			if (image.getWidth() != pw || image.getHeight() != ph) {
				throw new IOException("Heights do not match the preview");
			}
			for (int pz = 0; pz < ph; pz++) {
				int z = a.minZ() + pz * pstep;
				int gz = (z - b[1]) / step;
				if (gz < 0 || gz >= h) {
					continue;
				}
				for (int px = 0; px < pw; px++) {
					short y = ph16[pz * pw + px];
					int gx = (a.minX() + px * pstep - b[0]) / step;
					if (y == Short.MIN_VALUE || gx < 0 || gx >= w) {
						continue;
					}
					int i = gz * w + gx;
					if (y > heights[i]) {
						heights[i] = y;
						colors[i] = image.getPixel(px, pz);
					}
				}
			}
		}
	}

	/**
	 * {@code cell} is how many blocks one grid cell spans: a big area's preview has a pixel
	 * for several blocks, and heights must shrink with it or the city turns into needles.
	 */
	public static Raster rasterise(int[] colors, short[] heights, int w, int h, double cell) {
		return rasterise(colors, heights, w, h, cell, MAX_WIDTH);
	}

	/** As above, for a picture up to {@code maxWidth} pixels wide (a detailed one to zoom into). */
	public static Raster rasterise(int[] colors, short[] heights, int w, int h, double cell, int maxWidth) {
		// Merge columns when the area is too big for the picture.
		int k = Math.max(1, (int) Math.ceil((w + h) / (double) maxWidth));
		int cols = (w + k - 1) / k, rows = (h + k - 1) / k;
		int[] c = new int[cols * rows];
		int[] y = new int[cols * rows];
		int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
		for (int j = 0; j < rows; j++) {
			for (int i = 0; i < cols; i++) {
				int best = Short.MIN_VALUE, color = 0;
				for (int dz = 0; dz < k; dz++) {
					for (int dx = 0; dx < k; dx++) {
						int x = i * k + dx, z = j * k + dz;
						if (x < w && z < h && heights[z * w + x] > best) {
							best = heights[z * w + x];
							color = colors[z * w + x];
						}
					}
				}
				y[j * cols + i] = best;
				c[j * cols + i] = color;
				if (best != Short.MIN_VALUE) {
					minY = Math.min(minY, best);
					maxY = Math.max(maxY, best);
				}
			}
		}
		if (minY == Integer.MAX_VALUE) {
			minY = maxY = 0;
		}
		// One block of height is as tall on screen as half a column is wide (a true cube).
		int t = Math.max(1, Math.min(MAX_TILE, maxWidth / (cols + rows)));
		double unit = t / (k * cell);
		int relief = (int) Math.ceil((maxY - minY + 2) * unit);
		int width = (cols + rows) * t;
		int height = (cols + rows) * t / 2 + relief + t + 2;
		int[] out = new int[width * height];
		int ox = rows * t, oy = relief + 1;
		int[] lifts = new int[cols * rows];
		for (int idx = 0; idx < lifts.length; idx++) {
			lifts[idx] = y[idx] == Short.MIN_VALUE ? Integer.MIN_VALUE : (int) Math.round((y[idx] - minY + 1) * unit);
		}
		// Where nothing stands in front (the edge of the area), only a thin rim, not a wall to the bottom.
		int edge = Math.max(2, t);

		// Back to front: rows of equal i + j, so nearer columns paint over farther ones.
		for (int sum = 0; sum <= cols + rows - 2; sum++) {
			for (int i = Math.max(0, sum - rows + 1); i <= Math.min(cols - 1, sum); i++) {
				int j = sum - i;
				int idx = j * cols + i;
				if (y[idx] == Short.MIN_VALUE) {
					continue;
				}
				int base = c[idx] | 0xFF000000;
				int lift = lifts[idx];
				// Darker where a taller neighbour stands behind (a little ambient occlusion).
				int back = Math.max(i > 0 ? y[idx - 1] : Short.MIN_VALUE, j > 0 ? y[idx - cols] : Short.MIN_VALUE);
				float ao = back > y[idx] ? Math.max(0.82f, 1f - (back - y[idx]) * 0.04f) : 1f;
				// Walls only know their roof's colour; toned towards a neutral facade it reads as
				// one wall instead of a stripe per column.
				int wall = mix(base, 0xFFB9B2A8, 0.55f);
				int top = shade(base, 1.04f * ao), left = shade(wall, 0.80f), right = shade(wall, 0.64f);
				int cx = ox + (i - j) * t, cy = oy + (i + j) * t / 2;
				// A wall shows only down to the top of the column in front of it; one pixel more
				// so rounding leaves no seam. The left face looks towards +j, the right one towards +i.
				int frontLeft = j + 1 < rows ? lifts[idx + cols] : Integer.MIN_VALUE;
				int frontRight = i + 1 < cols ? lifts[idx + 1] : Integer.MIN_VALUE;
				boolean leftEdge = frontLeft == Integer.MIN_VALUE, rightEdge = frontRight == Integer.MIN_VALUE;
				int leftDepth = leftEdge ? edge : Math.max(0, lift - frontLeft) + 1;
				int rightDepth = rightEdge ? edge : Math.max(0, lift - frontRight) + 1;
				if (leftEdge) {
					left = shade(mix(base, 0xFF1C2024, 0.55f), 0.75f);
				}
				if (rightEdge) {
					right = shade(mix(base, 0xFF1C2024, 0.55f), 0.6f);
				}
				column(out, width, height, cx, cy - lift, leftDepth, rightDepth, t, top, left, right);
			}
		}

		NativeImage image = new NativeImage(width, height, true);
		for (int py = 0; py < height; py++) {
			for (int px = 0; px < width; px++) {
				image.setPixel(px, py, out[py * width + px]);
			}
		}
		return new Raster(image, oy, (cols + rows) * t, (cols + rows) * t / 2);
	}

	/** One column whose top rhombus starts at (cx, topY), with walls of the given depths below it. */
	private static void column(int[] out, int width, int height, int cx, int topY, int leftDepth, int rightDepth, int t,
							   int top, int left, int right) {
		for (int dx = -t; dx < t; dx++) {
			int x = cx + dx;
			if (x < 0 || x >= width) {
				continue;
			}
			double a = Math.abs(dx + 0.5) / t;
			int faceTop = (int) Math.round(topY + a * t / 2.0);
			int faceBottom = (int) Math.round(topY + t - a * t / 2.0);
			int sideBottom = faceBottom + (dx < 0 ? leftDepth : rightDepth);
			int side = dx < 0 ? left : right;
			for (int py = Math.max(0, faceTop); py < Math.min(height, sideBottom); py++) {
				int color;
				if (py < faceBottom) {
					// A light rim along the top edges gives every block a crisp outline.
					color = py == faceTop && t >= 3 ? shade(top, 1.10f) : top;
				} else {
					color = side;
				}
				out[py * width + x] = color;
			}
		}
	}

	private static int mix(int a, int b, float t) {
		int r = (int) (((a >> 16) & 0xFF) * (1 - t) + ((b >> 16) & 0xFF) * t);
		int g = (int) (((a >> 8) & 0xFF) * (1 - t) + ((b >> 8) & 0xFF) * t);
		int bl = (int) ((a & 0xFF) * (1 - t) + (b & 0xFF) * t);
		return 0xFF000000 | (r << 16) | (g << 8) | bl;
	}

	private static int shade(int argb, float f) {
		int r = Math.min(255, (int) (((argb >> 16) & 0xFF) * f));
		int g = Math.min(255, (int) (((argb >> 8) & 0xFF) * f));
		int b = Math.min(255, (int) ((argb & 0xFF) * f));
		return (argb & 0xFF000000) | (r << 16) | (g << 8) | b;
	}
}
