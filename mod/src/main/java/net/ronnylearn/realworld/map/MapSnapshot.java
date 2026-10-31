package net.ronnylearn.realworld.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.geo.WebMercator;

/**
 * A picture of the selected area, stitched together from map tiles. It becomes the
 * world's map.png, the base of the generation animation and the world icon.
 */
public final class MapSnapshot {
	private static final int TARGET = 640;

	private MapSnapshot() {
	}

	/** Builds the picture (blocking: downloads missing tiles). The caller owns the returned image. */
	public static NativeImage render(GeoBox box) throws Exception {
		TileStyle style = TileStyle.STANDARD;
		double x0 = WebMercator.x(box.minLng()), x1 = WebMercator.x(box.maxLng());
		double y0 = WebMercator.y(box.maxLat()), y1 = WebMercator.y(box.minLat());
		double span = Math.max(x1 - x0, y1 - y0);
		// The zoom at which the longer side is about TARGET pixels.
		int z = (int) Math.floor(Math.log(TARGET / (span * 256)) / Math.log(2));
		z = Math.max(1, Math.min(style.maxZoom, z));
		double worldPx = 256.0 * (1 << z);
		int px0 = (int) Math.floor(x0 * worldPx), px1 = (int) Math.ceil(x1 * worldPx);
		int py0 = (int) Math.floor(y0 * worldPx), py1 = (int) Math.ceil(y1 * worldPx);
		int width = Math.max(1, px1 - px0), height = Math.max(1, py1 - py0);

		NativeImage out = new NativeImage(width, height, false);
		out.fillRect(0, 0, width, height, 0xFFDDDDDD);
		try {
			for (int ty = py0 >> 8; ty <= (py1 - 1) >> 8; ty++) {
				for (int tx = px0 >> 8; tx <= (px1 - 1) >> 8; tx++) {
					try (NativeImage tile = NativeImage.read(TileCache.bytes(style, z, Math.floorMod(tx, 1 << z), ty))) {
						int srcX = Math.max(0, px0 - tx * 256), srcY = Math.max(0, py0 - ty * 256);
						int dstX = tx * 256 + srcX - px0, dstY = ty * 256 + srcY - py0;
						int w = Math.min(256 - srcX, width - dstX), h = Math.min(256 - srcY, height - dstY);
						for (int y = 0; y < h; y++) {
							for (int x = 0; x < w; x++) {
								out.setPixel(dstX + x, dstY + y, tile.getPixel(srcX + x, srcY + y) | 0xFF000000);
							}
						}
					}
				}
			}
		} catch (Exception e) {
			out.close();
			throw e;
		}
		return out;
	}

	/** A square, centred crop of the picture scaled to size×size. */
	public static NativeImage square(NativeImage source, int size) {
		int side = Math.min(source.getWidth(), source.getHeight());
		NativeImage out = new NativeImage(size, size, false);
		source.resizeSubRectTo((source.getWidth() - side) / 2, (source.getHeight() - side) / 2, side, side, out);
		return out;
	}
}
