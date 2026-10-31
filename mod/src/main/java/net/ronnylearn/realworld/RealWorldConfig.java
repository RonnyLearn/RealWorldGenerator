package net.ronnylearn.realworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.ronnylearn.realworld.arnis.GenerationSettings;
import net.ronnylearn.realworld.world.ZombieSettings;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Remembered between sessions: where the map was and the last generation options. */
public final class RealWorldConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static RealWorldConfig instance;

	public double lat = Double.NaN;
	public double lng = Double.NaN;
	public double zoom = 3;
	public String style = "standard";
	public double scale = 1.0;
	public String profile = "CITY";
	public String mode = "CITY";
	public double heightMultiplier = 1.0;
	public boolean interiors = false;
	public boolean winter = false;
	public boolean apocalypse = false;
	public String signage = "BASIC";
	public boolean caves = false;
	public boolean flat = true;
	public ZombieSettings zombies = new ZombieSettings();
	/** Finished generations on this computer: area at world scale, seconds, pieces, core share. */
	public java.util.List<double[]> speed = new java.util.ArrayList<>();
	public String load = "BALANCED";

	private static Path file() {
		return RealWorldMod.dataDir().resolve("config.json");
	}

	public static RealWorldConfig get() {
		if (instance == null) {
			try {
				instance = GSON.fromJson(Files.readString(file(), StandardCharsets.UTF_8), RealWorldConfig.class);
			} catch (Exception e) {
				instance = null;
			}
			if (instance == null) {
				instance = new RealWorldConfig();
			}
		}
		return instance;
	}

	public void save() {
		try {
			Files.createDirectories(file().getParent());
			Files.writeString(file(), GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("Could not save config: {}", e.toString());
		}
	}

	public GenerationSettings settings() {
		GenerationSettings s = new GenerationSettings();
		s.scale = scale;
		s.profile = parse(GenerationSettings.Profile.class, profile, GenerationSettings.Profile.CITY);
		s.mode = parse(GenerationSettings.Mode.class, mode, GenerationSettings.Mode.CITY);
		s.heightMultiplier = heightMultiplier;
		s.interiors = interiors;
		s.winter = winter;
		s.apocalypse = apocalypse;
		s.signage = parse(GenerationSettings.Signage.class, signage, GenerationSettings.Signage.BASIC);
		s.caves = caves;
		s.flatSurroundings = flat;
		s.zombies = zombies == null ? new ZombieSettings() : zombies.copy();
		s.load = parse(GenerationSettings.Load.class, load, GenerationSettings.Load.BALANCED);
		return s;
	}

	public void remember(GenerationSettings s) {
		scale = s.scale;
		profile = s.profile.name();
		mode = s.mode.name();
		heightMultiplier = s.heightMultiplier;
		interiors = s.interiors;
		winter = s.winter;
		apocalypse = s.apocalypse;
		signage = s.signage.name();
		caves = s.caves;
		flat = s.flatSurroundings;
		zombies = s.zombies.copy();
		load = s.load.name();
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
		try {
			return Enum.valueOf(type, name);
		} catch (Exception e) {
			return fallback;
		}
	}
}
