package net.ronnylearn.realworld.world;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.ronnylearn.realworld.map.MapSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * The world's picture: Arnis's top-down block render of every generated area, stitched
 * together at their real positions. A world grows area by area, so the picture is rebuilt
 * after every generation. Also writes icon.png for the vanilla world list.
 */
public final class WorldPicture {
	/** Longest side of the stitched picture; larger worlds are scaled down to fit. */
	private static final int MAX_SIDE = 2048;
	private static final int BACKGROUND = 0xFF14181C;

	/** One generated area: block bounds and its preview file. */
	public record Area(int minX, int minZ, int maxX, int maxZ, double minLat, double minLng, double maxLat, double maxLng, Path preview) {
	}

	private WorldPicture() {
	}

	/** The areas recorded in Arnis's One World manifest (empty for other worlds). */
	public static List<Area> areas(Path world) {
		List<Area> areas = new ArrayList<>();
		Path manifest = world.resolve("arnis_one_world.json");
		if (!Files.isRegularFile(manifest)) {
			return areas;
		}
		try {
			JsonObject root = JsonParser.parseString(Files.readString(manifest, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray list = root.getAsJsonArray("areas");
			for (JsonElement e : list) {
				JsonObject a = e.getAsJsonObject();
				Path preview = a.has("preview") && !a.get("preview").isJsonNull()
					? world.resolve(a.get("preview").getAsString()).normalize() : null;
				if (preview != null && !preview.startsWith(world.normalize())) {
					preview = null;
				}
				areas.add(new Area(a.get("min_x").getAsInt(), a.get("min_z").getAsInt(), a.get("max_x").getAsInt(), a.get("max_z").getAsInt(),
					a.get("min_lat").getAsDouble(), a.get("min_lon").getAsDouble(), a.get("max_lat").getAsDouble(), a.get("max_lon").getAsDouble(),
					preview));
			}
		} catch (Exception ignored) {
		}
		return areas;
	}

	/** Block bounds of all areas together: minX, minZ, maxX, maxZ; null without areas. */
	public static int[] bounds(List<Area> areas) {
		if (areas.isEmpty()) {
			return null;
		}
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (Area a : areas) {
			b[0] = Math.min(b[0], a.minX());
			b[1] = Math.min(b[1], a.minZ());
			b[2] = Math.max(b[2], a.maxX());
			b[3] = Math.max(b[3], a.maxZ());
		}
		return b;
	}

	/**
	 * Rebuilds blocks.png from the area previews (or takes a single Arnis preview found in the
	 * world folder) and icon.png from it. Blocking; call off the render thread.
	 */
	public static void rebuild(Path world) throws IOException {
		Files.createDirectories(WorldMeta.dir(world));
		Path loose = world.resolve("arnis_world_map.png");
		List<Area> areas = areas(world);
		int[] b = bounds(areas);
		if (b != null) {
			int width = b[2] - b[0] + 1, height = b[3] - b[1] + 1;
			int step = Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) MAX_SIDE));
			try (NativeImage out = new NativeImage(Math.max(1, width / step), Math.max(1, height / step), false)) {
				out.fillRect(0, 0, out.getWidth(), out.getHeight(), BACKGROUND);
				for (Area a : areas) {
					if (a.preview() == null || !Files.isRegularFile(a.preview())) {
						continue;
					}
					try (NativeImage img = NativeImage.read(Files.readAllBytes(a.preview()))) {
						// A preview covers its area's blocks, one pixel each.
						double sx = (double) img.getWidth() / (a.maxX() - a.minX() + 1);
						double sz = (double) img.getHeight() / (a.maxZ() - a.minZ() + 1);
						for (int z = a.minZ(); z <= a.maxZ(); z += step) {
							for (int x = a.minX(); x <= a.maxX(); x += step) {
								int px = (int) ((x - a.minX()) * sx), pz = (int) ((z - a.minZ()) * sz);
								int ox = (x - b[0]) / step, oz = (z - b[1]) / step;
								if (px < img.getWidth() && pz < img.getHeight() && ox < out.getWidth() && oz < out.getHeight()) {
									out.setPixel(ox, oz, img.getPixel(px, pz) | 0xFF000000);
								}
							}
						}
					}
				}
				out.writeToFile(WorldMeta.blocksImage(world));
			}
		} else if (Files.isRegularFile(loose)) {
			Files.move(loose, WorldMeta.blocksImage(world), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
		}
		Files.deleteIfExists(loose);

		Path source = WorldMeta.picture(world);
		if (Files.isRegularFile(source)) {
			try (NativeImage full = NativeImage.read(Files.readAllBytes(source)); NativeImage icon = MapSnapshot.square(full, 64)) {
				icon.writeToFile(world.resolve("icon.png"));
			}
		}
	}
}
