package net.ronnylearn.realworld.map;

import com.mojang.blaze3d.platform.cursor.CursorType;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.geo.WebMercator;
import net.ronnylearn.realworld.ui.Ui;

/**
 * A slippy map with an area selection. Positions are kept in normalised Web Mercator
 * (the world is 0..1 on both axes), so panning and zooming never lose precision.
 * Left drag pans (or selects with the selection tool / Shift), right drag always selects,
 * the selection can be moved and resized by its edges and corners.
 */
public final class MapView {
	public enum Tool { PAN, SELECT, SPAWN }

	private enum Drag { NONE, PAN, CREATE, MOVE, N, S, E, W, NE, NW, SE, SW }

	private static final double MIN_ZOOM = 1.5;
	private static final int HANDLE = 4;

	public int x, y, w, h;
	public TileStyle style = TileStyle.STANDARD;
	public Tool tool = Tool.PAN;
	/** World scale chosen in the sidebar, for the chunk grid and block counts. */
	public double scale = 1.0;
	public Runnable onSelectionChanged = () -> { };
	public Runnable onSpawnChanged = () -> { };
	/** Areas already in the world being extended, drawn in green under the selection. */
	public final java.util.List<GeoBox> existing = new java.util.ArrayList<>();

	private double cx = 0.5, cy = 0.5, zoom = 3, targetZoom = 3;
	private double anchorX = Double.NaN, anchorY, anchorSX, anchorSY;
	private Flight flight;

	private double[] sel;
	private double[] selAtPress;
	private double spawnX = Double.NaN, spawnY;
	private double spawnAtPressX, spawnAtPressY;
	private Drag drag = Drag.NONE;
	private double pressMX, pressMY, pressSX, pressSY, pressCX, pressCY;
	private long lastFrame;
	private double hoverLat = Double.NaN, hoverLng;

	private record Flight(double x0, double y0, double z0, double x1, double y1, double z1, double arc, long start, double seconds) {
	}

	// ---------------------------------------------------------------- coordinates

	private static int guiScale() {
		return Math.max(1, Minecraft.getInstance().getWindow().getGuiScale());
	}

	/** GUI units per normalised unit at the current zoom (tiles show at 1:1 physical pixels). */
	private double unit() {
		return 256.0 * Math.pow(2, zoom) / guiScale();
	}

	private double sx(double mx) {
		return x + w / 2.0 + (mx - cx) * unit();
	}

	private double sy(double my) {
		return y + h / 2.0 + (my - cy) * unit();
	}

	private double mx(double sx) {
		return cx + (sx - x - w / 2.0) / unit();
	}

	private double my(double sy) {
		return cy + (sy - y - h / 2.0) / unit();
	}

	private double maxZoom() {
		return style.maxZoom + 1.0;
	}

	public double centerLat() {
		return WebMercator.lat(cy);
	}

	public double centerLng() {
		return WebMercator.lng(cx);
	}

	public double zoom() {
		return targetZoom;
	}

	public void setView(double lat, double lng, double zoom) {
		cx = WebMercator.x(lng);
		cy = WebMercator.y(lat);
		this.zoom = targetZoom = clampZoom(zoom);
		flight = null;
	}

	private double clampZoom(double z) {
		return Math.max(MIN_ZOOM, Math.min(maxZoom(), z));
	}

	/** Metres on the ground per GUI unit at the map centre. */
	private double metersPerGui() {
		return WebMercator.metersPerUnit(centerLat()) / unit();
	}

	// ---------------------------------------------------------------- selection

	public GeoBox selection() {
		if (sel == null) {
			return null;
		}
		return GeoBox.of(WebMercator.lat(sel[3]), WebMercator.lng(sel[0]), WebMercator.lat(sel[1]), WebMercator.lng(sel[2]));
	}

	public void setSelection(GeoBox box) {
		if (box == null) {
			sel = null;
		} else {
			sel = new double[] {WebMercator.x(box.minLng()), WebMercator.y(box.maxLat()), WebMercator.x(box.maxLng()), WebMercator.y(box.minLat())};
		}
		dropSpawnOutside();
		onSelectionChanged.run();
	}

	public boolean hasSpawn() {
		return !Double.isNaN(spawnX);
	}

	public double spawnLat() {
		return WebMercator.lat(spawnY);
	}

	public double spawnLng() {
		return WebMercator.lng(spawnX);
	}

	public void clearSpawn() {
		spawnX = Double.NaN;
		onSpawnChanged.run();
	}

	private void dropSpawnOutside() {
		if (hasSpawn() && (sel == null || spawnX < sel[0] || spawnX > sel[2] || spawnY < sel[1] || spawnY > sel[3])) {
			clearSpawn();
		}
	}

	/** Lat/lng under the mouse, for the coordinate readout; NaN when the mouse is off the map. */
	public double hoverLat() {
		return hoverLat;
	}

	public double hoverLng() {
		return hoverLng;
	}

	// ---------------------------------------------------------------- camera moves

	/** Flies to a point: zooms out a little on long trips, then in, over about a second and a half. */
	public void flyTo(double lat, double lng, double toZoom) {
		double tx = WebMercator.x(lng), ty = WebMercator.y(lat);
		double distance = Math.hypot(tx - cx, ty - cy) * Math.pow(2, zoom);
		double arc = Math.min(5, Math.max(0, Math.log(distance + 1) / Math.log(2) - 1));
		double seconds = 0.9 + Math.min(1.1, arc * 0.22);
		flight = new Flight(cx, cy, zoom, tx, ty, clampZoom(toZoom), arc, System.nanoTime(), seconds);
		anchorX = Double.NaN;
	}

	/** Flies so the box fills the map with some margin. */
	public void flyToFit(GeoBox box) {
		double bw = WebMercator.x(box.maxLng()) - WebMercator.x(box.minLng());
		double bh = WebMercator.y(box.minLat()) - WebMercator.y(box.maxLat());
		double fit = Math.min((w * 0.62) / Math.max(bw, 1e-9), (h * 0.62) / Math.max(bh, 1e-9));
		double z = Math.log(fit * guiScale() / 256.0) / Math.log(2);
		flyTo(box.centerLat(), box.centerLng(), Math.min(z, 17.5));
	}

	public void zoomBy(double steps) {
		zoomAround(x + w / 2.0, y + h / 2.0, steps);
	}

	private void zoomAround(double sx, double sy, double steps) {
		flight = null;
		anchorX = mx(sx);
		anchorY = my(sy);
		anchorSX = sx;
		anchorSY = sy;
		targetZoom = clampZoom(targetZoom + steps);
	}

	private void animate(double dt) {
		if (flight != null) {
			double t = Math.min(1, (System.nanoTime() - flight.start) / 1e9 / flight.seconds);
			double e = t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
			cx = flight.x0 + (flight.x1 - flight.x0) * e;
			cy = flight.y0 + (flight.y1 - flight.y0) * e;
			zoom = flight.z0 + (flight.z1 - flight.z0) * e - flight.arc * Math.sin(Math.PI * t);
			targetZoom = zoom;
			if (t >= 1) {
				zoom = targetZoom = flight.z1;
				flight = null;
			}
			return;
		}
		if (Math.abs(targetZoom - zoom) > 1e-4) {
			zoom += (targetZoom - zoom) * (1 - Math.exp(-dt * 14));
			if (Math.abs(targetZoom - zoom) < 1e-3) {
				zoom = targetZoom;
			}
			if (!Double.isNaN(anchorX)) {
				// Keep the point under the cursor where it is while zooming around it.
				cx = anchorX - (anchorSX - x - w / 2.0) / unit();
				cy = anchorY - (anchorSY - y - h / 2.0) / unit();
			}
		}
		cy = Math.max(0, Math.min(1, cy));
		cx = Math.max(0, Math.min(1, cx));
	}

	// ---------------------------------------------------------------- drawing

	public void render(GuiGraphicsExtractor g, Font font, int mouseX, int mouseY) {
		long now = System.nanoTime();
		double dt = lastFrame == 0 ? 0 : Math.min(0.1, (now - lastFrame) / 1e9);
		lastFrame = now;
		animate(dt);

		if (contains(mouseX, mouseY)) {
			hoverLat = WebMercator.lat(my(mouseY));
			hoverLng = WebMercator.lng(mx(mouseX));
		} else {
			hoverLat = Double.NaN;
		}

		g.enableScissor(x, y, x + w, y + h);
		g.fill(x, y, x + w, y + h, 0xFF1A1E23);
		drawTiles(g);
		drawExisting(g);
		if (sel != null) {
			drawSelection(g, font, now);
		}
		if (hasSpawn()) {
			drawSpawn(g, font);
		}
		g.disableScissor();

		CursorType cursor = cursor(mouseX, mouseY);
		if (cursor != null) {
			g.requestCursor(cursor);
		}
	}

	private void drawTiles(GuiGraphicsExtractor g) {
		TileCache cache = TileCache.get();
		cache.beginFrame();
		int tz = Math.max(0, Math.min(style.maxZoom, (int) Math.floor(zoom + 0.3)));
		int n = 1 << tz;
		double tile = unit() / n;
		double left = mx(x), top = my(y), right = mx(x + w), bottom = my(y + h);
		int tx0 = (int) Math.floor(left * n), tx1 = (int) Math.floor(right * n);
		int ty0 = Math.max(0, (int) Math.floor(top * n)), ty1 = Math.min(n - 1, (int) Math.floor(bottom * n));
		if ((long) (tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 600) {
			return;
		}
		for (int ty = ty0; ty <= ty1; ty++) {
			for (int tx = tx0; tx <= tx1; tx++) {
				double sx = sx((double) tx / n), sy = sy((double) ty / n);
				Identifier id = cache.texture(style, tz, tx, ty);
				if (id != null) {
					blitTile(g, id, sx, sy, tile, 0, 0, 1);
					continue;
				}
				// Not loaded yet: stretch a coarser tile that is, so the map never shows holes.
				boolean drawn = false;
				for (int d = 1; d <= 6 && tz - d >= 0 && !drawn; d++) {
					int f = 1 << d;
					int px = Math.floorDiv(tx, f), py = ty >> d;
					Identifier parent = cache.loaded(style, tz - d, px, py);
					if (parent != null) {
						float size = 1f / f;
						blitTile(g, parent, sx, sy, tile, (Math.floorMod(tx, f)) * size, (ty - py * f) * size, size);
						drawn = true;
					}
				}
				if (!drawn) {
					int ix = (int) Math.floor(sx), iy = (int) Math.floor(sy), size = (int) Math.ceil(tile);
					g.fill(ix, iy, ix + size, iy + size, ((tx + ty) & 1) == 0 ? 0xFF22272D : 0xFF1E2227);
				}
			}
		}
	}

	private static void blitTile(GuiGraphicsExtractor g, Identifier id, double sx, double sy, double size, float u, float v, float span) {
		g.pose().pushMatrix();
		g.pose().translate((float) sx, (float) sy);
		// A hair of overlap hides seams between neighbouring tiles.
		g.pose().scale((float) ((size + 0.35) / 256.0));
		g.blit(id, 0, 0, 256, 256, u, u + span, v, v + span);
		g.pose().popMatrix();
	}

	private void drawExisting(GuiGraphicsExtractor g) {
		for (GeoBox box : existing) {
			int x0 = (int) Math.round(sx(WebMercator.x(box.minLng()))), y0 = (int) Math.round(sy(WebMercator.y(box.maxLat())));
			int x1 = (int) Math.round(sx(WebMercator.x(box.maxLng()))), y1 = (int) Math.round(sy(WebMercator.y(box.minLat())));
			g.fill(x0, y0, x1, y1, 0x3A55C25A);
			g.outline(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), Ui.ACCENT);
		}
	}

	private void drawSelection(GuiGraphicsExtractor g, Font font, long now) {
		int x0 = (int) Math.round(sx(sel[0])), y0 = (int) Math.round(sy(sel[1]));
		int x1 = (int) Math.round(sx(sel[2])), y1 = (int) Math.round(sy(sel[3]));

		// Dim everything outside, tint inside.
		int dim = 0x66000000;
		g.fill(x, y, x + w, y0, dim);
		g.fill(x, y1, x + w, y + h, dim);
		g.fill(x, Math.max(y, y0), x0, Math.min(y + h, y1), dim);
		g.fill(x1, Math.max(y, y0), x + w, Math.min(y + h, y1), dim);
		g.fill(x0, y0, x1, y1, 0x163FA9FF);

		drawChunkGrid(g, x0, y0, x1, y1);

		// Marching-ants border over a solid blue line.
		g.outline(x0 - 1, y0 - 1, x1 - x0 + 2, y1 - y0 + 2, Ui.SELECTION);
		int offset = (int) ((now / 40_000_000L) % 10);
		dashes(g, x0, y0, x1, y1, offset);

		for (int[] p : new int[][] {{x0, y0}, {x1, y0}, {x0, y1}, {x1, y1}}) {
			handle(g, p[0], p[1], HANDLE);
		}
		for (int[] p : new int[][] {{(x0 + x1) / 2, y0}, {(x0 + x1) / 2, y1}, {x0, (y0 + y1) / 2}, {x1, (y0 + y1) / 2}}) {
			handle(g, p[0], p[1], HANDLE - 1);
		}

		GeoBox box = selection();
		String wText = Ui.distance(box.widthMeters());
		String hText = Ui.distance(box.heightMeters());
		pill(g, font, wText, (x0 + x1) / 2 - font.width(wText) / 2, y0 - 16);
		pill(g, font, hText, x1 + 9, (y0 + y1) / 2 - 4);
	}

	private void drawChunkGrid(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1) {
		double chunkGui = (16.0 / scale) / metersPerGui();
		if (chunkGui < 7) {
			return;
		}
		int alpha = (int) Math.min(0x30, (chunkGui - 7) * 6);
		int color = (alpha << 24) | 0xFFFFFF;
		int lines = 0;
		for (double gx = x0 + chunkGui; gx < x1 && lines < 300; gx += chunkGui, lines++) {
			g.verticalLine((int) gx, y0, y1, color);
		}
		for (double gy = y0 + chunkGui; gy < y1 && lines < 600; gy += chunkGui, lines++) {
			g.horizontalLine(x0, x1, (int) gy, color);
		}
	}

	private static void dashes(GuiGraphicsExtractor g, int x0, int y0, int x1, int y1, int offset) {
		int dash = 6, period = 10;
		for (int px = x0 - offset; px < x1; px += period) {
			int a = Math.max(x0, px), b = Math.min(x1, px + dash);
			if (b > a) {
				g.fill(a, y0, b, y0 + 1, 0xFFFFFFFF);
				g.fill(a, y1 - 1, b, y1, 0xFFFFFFFF);
			}
		}
		for (int py = y0 - offset; py < y1; py += period) {
			int a = Math.max(y0, py), b = Math.min(y1, py + dash);
			if (b > a) {
				g.fill(x0, a, x0 + 1, b, 0xFFFFFFFF);
				g.fill(x1 - 1, a, x1, b, 0xFFFFFFFF);
			}
		}
	}

	private static void handle(GuiGraphicsExtractor g, int px, int py, int r) {
		g.fill(px - r - 1, py - r - 1, px + r + 1, py + r + 1, Ui.SELECTION);
		g.fill(px - r, py - r, px + r, py + r, 0xFFFFFFFF);
	}

	private static void pill(GuiGraphicsExtractor g, Font font, String text, int px, int py) {
		int tw = font.width(text);
		g.fill(px - 4, py - 3, px + tw + 4, py + 10, 0xE0101418);
		g.outline(px - 4, py - 3, tw + 8, 13, Ui.SELECTION);
		g.text(font, text, px, py, Ui.TEXT, false);
	}

	private void drawSpawn(GuiGraphicsExtractor g, Font font) {
		int px = (int) Math.round(sx(spawnX)), py = (int) Math.round(sy(spawnY));
		g.fill(px - 1, py - 14, px + 1, py, 0xFF202020);
		g.fill(px - 5, py - 20, px + 5, py - 10, 0xFF202020);
		g.fill(px - 4, py - 19, px + 4, py - 11, Ui.ACCENT);
		g.fill(px - 2, py - 1, px + 2, py + 1, 0xFF202020);
		String label = net.minecraft.network.chat.Component.translatable("realworld.map.spawn_label").getString();
		pill(g, font, label, px + 9, py - 19);
	}

	// ---------------------------------------------------------------- input

	public boolean contains(double mouseX, double mouseY) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}

	private Drag handleAt(double mouseX, double mouseY) {
		if (sel == null) {
			return Drag.NONE;
		}
		double x0 = sx(sel[0]), y0 = sy(sel[1]), x1 = sx(sel[2]), y1 = sy(sel[3]);
		double tol = HANDLE + 3;
		boolean left = Math.abs(mouseX - x0) <= tol, right = Math.abs(mouseX - x1) <= tol;
		boolean top = Math.abs(mouseY - y0) <= tol, bottom = Math.abs(mouseY - y1) <= tol;
		boolean inX = mouseX > x0 - tol && mouseX < x1 + tol, inY = mouseY > y0 - tol && mouseY < y1 + tol;
		if (top && left) return Drag.NW;
		if (top && right) return Drag.NE;
		if (bottom && left) return Drag.SW;
		if (bottom && right) return Drag.SE;
		if (top && inX) return Drag.N;
		if (bottom && inX) return Drag.S;
		if (left && inY) return Drag.W;
		if (right && inY) return Drag.E;
		if (mouseX > x0 && mouseX < x1 && mouseY > y0 && mouseY < y1) return Drag.MOVE;
		return Drag.NONE;
	}

	public CursorType cursor(double mouseX, double mouseY) {
		if (!contains(mouseX, mouseY) && drag == Drag.NONE) {
			return null;
		}
		Drag d = drag != Drag.NONE ? drag : handleAt(mouseX, mouseY);
		return switch (d) {
			case N, S -> CursorTypes.RESIZE_NS;
			case E, W -> CursorTypes.RESIZE_EW;
			case NE, NW, SE, SW, MOVE -> CursorTypes.RESIZE_ALL;
			case CREATE -> CursorTypes.CROSSHAIR;
			case PAN -> CursorTypes.RESIZE_ALL;
			case NONE -> tool == Tool.PAN ? null : CursorTypes.CROSSHAIR;
		};
	}

	/** Mouse press inside the map. */
	public void press(double mouseX, double mouseY, int button, boolean shift, boolean doubleClick) {
		flight = null;
		pressMX = mx(mouseX);
		pressMY = my(mouseY);
		pressSX = mouseX;
		pressSY = mouseY;
		pressCX = cx;
		pressCY = cy;
		selAtPress = sel == null ? null : sel.clone();
		spawnAtPressX = spawnX;
		spawnAtPressY = spawnY;
		if (button == 2) {
			drag = Drag.PAN;
			return;
		}
		if (button == 1) {
			drag = Drag.CREATE;
			return;
		}
		if (tool == Tool.SPAWN) {
			if (sel != null && pressMX >= sel[0] && pressMX <= sel[2] && pressMY >= sel[1] && pressMY <= sel[3]) {
				spawnX = pressMX;
				spawnY = pressMY;
				onSpawnChanged.run();
			}
			drag = Drag.NONE;
			return;
		}
		Drag handle = handleAt(mouseX, mouseY);
		if (handle != Drag.NONE && !(handle == Drag.MOVE && (tool == Tool.SELECT || shift))) {
			drag = handle;
		} else if (tool == Tool.SELECT || shift) {
			drag = Drag.CREATE;
		} else {
			drag = Drag.PAN;
			if (doubleClick) {
				zoomAround(mouseX, mouseY, 1);
				drag = Drag.NONE;
			}
		}
	}

	public void dragTo(double mouseX, double mouseY) {
		double mx = mx(mouseX), my = my(mouseY);
		switch (drag) {
			case PAN -> {
				cx = pressCX - (mouseX - pressSX) / unit();
				cy = pressCY - (mouseY - pressSY) / unit();
				anchorX = Double.NaN;
			}
			case CREATE -> {
				if (Math.abs(mouseX - pressSX) > 2 || Math.abs(mouseY - pressSY) > 2) {
					sel = new double[] {Math.min(pressMX, mx), Math.min(pressMY, my), Math.max(pressMX, mx), Math.max(pressMY, my)};
					dropSpawnOutside();
					onSelectionChanged.run();
				}
			}
			case MOVE -> {
				double dx = mx - pressMX, dy = my - pressMY;
				sel = new double[] {selAtPress[0] + dx, selAtPress[1] + dy, selAtPress[2] + dx, selAtPress[3] + dy};
				if (hasSpawn()) {
					// The spawn point travels with the area it belongs to.
					spawnX = spawnAtPressX + dx;
					spawnY = spawnAtPressY + dy;
				}
				onSelectionChanged.run();
			}
			case NONE -> {
			}
			default -> {
				double[] s = selAtPress.clone();
				if (drag == Drag.W || drag == Drag.NW || drag == Drag.SW) s[0] = mx;
				if (drag == Drag.E || drag == Drag.NE || drag == Drag.SE) s[2] = mx;
				if (drag == Drag.N || drag == Drag.NW || drag == Drag.NE) s[1] = my;
				if (drag == Drag.S || drag == Drag.SW || drag == Drag.SE) s[3] = my;
				sel = new double[] {Math.min(s[0], s[2]), Math.min(s[1], s[3]), Math.max(s[0], s[2]), Math.max(s[1], s[3])};
				dropSpawnOutside();
				onSelectionChanged.run();
			}
		}
	}

	/**
	 * Ends a drag. A plain click (no movement) on the map outside the selected area clears the
	 * selection, so a frame left on the map can be removed without drawing a new one.
	 */
	public void release(double mouseX, double mouseY) {
		Drag was = drag;
		drag = Drag.NONE;
		boolean click = Math.abs(mouseX - pressSX) <= 3 && Math.abs(mouseY - pressSY) <= 3;
		boolean outside = sel == null || pressMX < sel[0] || pressMX > sel[2] || pressMY < sel[1] || pressMY > sel[3];
		if (click && outside && sel != null && (was == Drag.PAN || was == Drag.CREATE)) {
			setSelection(null);
		}
	}

	public boolean dragging() {
		return drag != Drag.NONE;
	}

	public void scroll(double mouseX, double mouseY, double amount) {
		zoomAround(mouseX, mouseY, amount * 0.5);
	}

	public void pan(double dxGui, double dyGui) {
		flight = null;
		cx += dxGui / unit();
		cy += dyGui / unit();
	}
}
