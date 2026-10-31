package net.ronnylearn.realworld.world;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.ronnylearn.realworld.geo.GeoBox;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * What the mod knows about a world it generated, kept in the world folder as
 * realworld/meta.json next to the street map of the area (map.png) and the top-down render
 * of the generated blocks (blocks.png), which is the world's picture.
 */
public final class WorldMeta {
	public static final String DIR = "realworld";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public int format = 1;
	public String name = "";
	public String place = "";
	public double minLat, minLng, maxLat, maxLng;
	public double scale = 1.0;
	public boolean terrain = true;
	public boolean interiors;
	public boolean winter;
	public boolean apocalypse;
	public String worldType = "void";
	public String profile = "CITY";
	public String mode = "CITY";
	public String signage = "BASIC";
	public boolean caves;
	public double heightMultiplier = 1.0;
	public ZombieSettings zombies = new ZombieSettings();
	/** How many areas the world is made of; each "Add area" adds one. */
	public int areaCount = 1;
	public long lastExtended;
	public long created;
	public long lastPlayed;
	public int generationSeconds;
	public String arnisVersion = "";
	/** Block bounds of the generated area: minX, minZ, maxX, maxZ. */
	public int[] area;
	/** Arnis's spawn point: x, y, z. */
	public int[] spawn;
	/**
	 * Pieces of a big area not built yet (minLat, minLng, maxLat, maxLng each), when a
	 * generation was stopped or the game closed; "Finish" in the library builds them.
	 */
	public java.util.List<double[]> pending = new java.util.ArrayList<>();
	/** How many pieces that generation had in all. */
	public int pendingTotal;
	/** The whole area of that generation (minLat, minLng, maxLat, maxLng), for drawing it. */
	public double[] pendingBox;
	/** The settings it ran with, so the rest is built the same way. */
	public net.ronnylearn.realworld.arnis.GenerationSettings pendingSettings;

	/** How far the player last chose to prepare chunks on entry (a ChunkPreloader.Range). */
	public String preload = "NEAR";
	/** Chunk rectangles already prepared (minX, minZ, maxX, maxZ), so they are not done twice. */
	public java.util.List<int[]> preloaded = new java.util.ArrayList<>();

	public boolean unfinished() {
		return pending != null && !pending.isEmpty() && pendingSettings != null;
	}

	public GeoBox bounds() {
		return new GeoBox(minLat, minLng, maxLat, maxLng);
	}

	public void setBounds(GeoBox box) {
		minLat = box.minLat();
		minLng = box.minLng();
		maxLat = box.maxLat();
		maxLng = box.maxLng();
	}

	/** Block x/z of a point inside the area. Arnis maps longitude to x and latitude to z (north is -z). */
	public int[] toBlock(double lat, double lng) {
		if (area == null) {
			return null;
		}
		double fx = (lng - minLng) / (maxLng - minLng);
		double fz = (maxLat - lat) / (maxLat - minLat);
		return new int[] {
			(int) Math.round(area[0] + fx * (area[2] - area[0])),
			(int) Math.round(area[1] + fz * (area[3] - area[1]))
		};
	}

	public static Path dir(Path world) {
		return world.resolve(DIR);
	}

	public static Path mapImage(Path world) {
		return dir(world).resolve("map.png");
	}

	/** Arnis's top-down render of the whole generated area, one pixel per block. */
	public static Path blocksImage(Path world) {
		return dir(world).resolve("blocks.png");
	}

	/** The world's picture: the block render, or the street map for worlds made before it existed. */
	public static Path picture(Path world) {
		Path blocks = blocksImage(world);
		return Files.isRegularFile(blocks) ? blocks : mapImage(world);
	}

	public static WorldMeta read(Path world) {
		Path file = dir(world).resolve("meta.json");
		if (!Files.isRegularFile(file)) {
			return null;
		}
		try {
			return GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), WorldMeta.class);
		} catch (Exception e) {
			return null;
		}
	}

	public void write(Path world) throws IOException {
		Files.createDirectories(dir(world));
		Files.writeString(dir(world).resolve("meta.json"), GSON.toJson(this), StandardCharsets.UTF_8);
	}
}
