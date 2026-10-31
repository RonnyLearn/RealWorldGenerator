package net.ronnylearn.realworld.map;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.ronnylearn.realworld.RealWorldMod;
import net.ronnylearn.realworld.net.Http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;

/**
 * Map tiles as GPU textures. Tiles are downloaded on worker threads, kept on disk for a
 * while (OpenStreetMap asks clients to cache) and turned into textures on the render thread.
 * The newest requests load first, so the part of the map being looked at fills in first.
 */
public final class TileCache {
	private static final int WORKERS = 3;
	private static final int MAX_TEXTURES = 420;
	private static final Duration DISK_TTL = Duration.ofDays(14);
	private static final long RETRY_MS = 8_000;
	private static final long STALE_MS = 2_500;

	private static final TileCache INSTANCE = new TileCache();

	private final Map<String, Tile> tiles = new LinkedHashMap<>(256, 0.75f, true);
	private final BlockingDeque<Tile> queue = new LinkedBlockingDeque<>();
	private long frame;

	public static TileCache get() {
		return INSTANCE;
	}

	private TileCache() {
		for (int i = 0; i < WORKERS; i++) {
			Thread worker = new Thread(this::work, "RealWorld-Tiles-" + i);
			worker.setDaemon(true);
			worker.start();
		}
	}

	private static final class Tile {
		final TileStyle style;
		final int z, x, y;
		final String key;
		volatile Identifier texture;
		volatile boolean loading;
		volatile long failedAt;
		volatile long requestedAt;
		long usedFrame;

		Tile(TileStyle style, int z, int x, int y, String key) {
			this.style = style;
			this.z = z;
			this.x = x;
			this.y = y;
			this.key = key;
		}
	}

	/** Call once per rendered frame before asking for tiles. */
	public void beginFrame() {
		frame++;
	}

	/** The tile's texture when it is loaded, otherwise null (and the tile is queued). */
	public Identifier texture(TileStyle style, int z, int x, int y) {
		return lookup(style, z, x, y, true);
	}

	/** The texture only if it is already loaded; never queues a download. */
	public Identifier loaded(TileStyle style, int z, int x, int y) {
		return lookup(style, z, x, y, false);
	}

	private Identifier lookup(TileStyle style, int z, int x, int y, boolean request) {
		int n = 1 << z;
		if (y < 0 || y >= n) {
			return null;
		}
		x = Math.floorMod(x, n);
		String key = style.id + "/" + z + "/" + x + "/" + y;
		Tile tile;
		synchronized (tiles) {
			tile = tiles.get(key);
			if (tile == null) {
				if (!request) {
					return null;
				}
				tile = new Tile(style, z, x, y, key);
				tiles.put(key, tile);
			}
		}
		tile.usedFrame = frame;
		if (tile.texture != null) {
			return tile.texture;
		}
		if (request && !tile.loading && System.currentTimeMillis() - tile.failedAt > RETRY_MS) {
			tile.loading = true;
			tile.requestedAt = System.currentTimeMillis();
			queue.offerFirst(tile);
		} else if (request) {
			tile.requestedAt = System.currentTimeMillis();
		}
		return null;
	}

	private void work() {
		while (true) {
			Tile tile;
			try {
				tile = queue.takeFirst();
			} catch (InterruptedException e) {
				return;
			}
			// The map moved on; a tile nobody looks at any more waits for the next request.
			if (System.currentTimeMillis() - tile.requestedAt > STALE_MS) {
				tile.loading = false;
				continue;
			}
			try {
				byte[] png = bytes(tile.style, tile.z, tile.x, tile.y);
				NativeImage image = NativeImage.read(png);
				Minecraft.getInstance().execute(() -> upload(tile, image));
			} catch (Exception e) {
				tile.failedAt = System.currentTimeMillis();
				tile.loading = false;
			}
		}
	}

	private void upload(Tile tile, NativeImage image) {
		Identifier id = Identifier.fromNamespaceAndPath(RealWorldMod.MOD_ID, "tile/" + tile.key);
		Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture("Real World tile " + tile.key, image));
		tile.texture = id;
		tile.loading = false;
		evict();
	}

	private void evict() {
		synchronized (tiles) {
			int over = tiles.size() - MAX_TEXTURES;
			Iterator<Tile> it = tiles.values().iterator();
			while (over > 0 && it.hasNext()) {
				Tile tile = it.next();
				if (tile.usedFrame >= frame - 2 || tile.loading) {
					continue;
				}
				if (tile.texture != null) {
					Minecraft.getInstance().getTextureManager().release(tile.texture);
				}
				it.remove();
				over--;
			}
		}
	}

	/** Frees every tile texture, e.g. when the map screen closes. */
	public void releaseAll() {
		synchronized (tiles) {
			for (Tile tile : tiles.values()) {
				if (tile.texture != null) {
					Minecraft.getInstance().getTextureManager().release(tile.texture);
				}
			}
			tiles.clear();
		}
		queue.clear();
	}

	/** PNG bytes of a tile, from the disk cache when fresh enough. Blocking; worker threads only. */
	public static byte[] bytes(TileStyle style, int z, int x, int y) throws IOException, InterruptedException {
		Path file = RealWorldMod.dataDir().resolve("tiles").resolve(style.id).resolve(Integer.toString(z))
			.resolve(Integer.toString(x)).resolve(y + ".png");
		if (Files.isRegularFile(file)) {
			FileTime modified = Files.getLastModifiedTime(file);
			if (modified.toInstant().plus(DISK_TTL).isAfter(Instant.now())) {
				return Files.readAllBytes(file);
			}
		}
		byte[] png = Http.get(style.url(z, x, y), null);
		if (style.jpeg) {
			png = toPng(png);
		}
		Files.createDirectories(file.getParent());
		Path tmp = Files.createTempFile(file.getParent(), "tile", ".tmp");
		Files.write(tmp, png);
		Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		return png;
	}

	private static byte[] toPng(byte[] jpeg) throws IOException {
		java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(jpeg));
		if (image == null) {
			throw new IOException("Unreadable tile image");
		}
		java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(jpeg.length * 3);
		javax.imageio.ImageIO.write(image, "png", out);
		return out.toByteArray();
	}
}
