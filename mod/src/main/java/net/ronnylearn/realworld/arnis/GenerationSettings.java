package net.ronnylearn.realworld.arnis;

import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.world.ZombieSettings;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Everything the player chose for a world, turned into Arnis arguments. */
public final class GenerationSettings {
	public static final double[] SCALES = {0.5, 0.75, 1.0, 1.5, 2.0, 3.0};
	public static final double[] HEIGHTS = {0.5, 1.0, 1.5, 2.0, 3.0};

	/** What Arnis builds (its --mode). */
	public enum Mode {
		CITY("geo-terrain"), FLAT("geo-only"), NATURE("terrain-only"), BLUEPRINT("blueprint");

		public final String arg;

		Mode(String arg) {
			this.arg = arg;
		}

		public boolean terrain() {
			return this != FLAT;
		}
	}

	public enum Signage { NONE, BASIC, FULL }

	/**
	 * How hard generation may work the computer: its share of the cores and of the free memory
	 * (which sets the size of each piece), and whether its threads yield to the game.
	 */
	public enum Load {
		LIGHT(0.25, true, 0.25, 2), BALANCED(0.5, true, 0.45, 6), MAX(0.9, false, 0.7, 25);

		public final double cpu;
		public final boolean lowPriority;
		public final double ramShare;
		public final double maxPieceKm2;

		Load(double cpu, boolean lowPriority, double ramShare, double maxPieceKm2) {
			this.cpu = cpu;
			this.lowPriority = lowPriority;
			this.ramShare = ramShare;
			this.maxPieceKm2 = maxPieceKm2;
		}

		/** Threads for Arnis; 0 leaves it its own pool. */
		public int threads() {
			return this == MAX ? 0 : Math.max(1, (int) (Runtime.getRuntime().availableProcessors() * cpu));
		}
	}

	/**
	 * Ready-made worlds, like Lost Cities profiles: each sets the options below in one click.
	 * Changing any option afterwards makes the profile CUSTOM.
	 */
	public enum Profile {
		CITY, LIVING, ABANDONED, WASTELAND, WINTER, FLAT, NATURE, BLUEPRINT, CUSTOM;

		public void apply(GenerationSettings s) {
			if (this == CUSTOM) {
				return;
			}
			s.mode = switch (this) {
				case FLAT -> Mode.FLAT;
				case NATURE -> Mode.NATURE;
				case BLUEPRINT -> Mode.BLUEPRINT;
				default -> Mode.CITY;
			};
			s.interiors = this == LIVING || this == ABANDONED || this == WASTELAND || this == WINTER;
			s.winter = this == WINTER;
			s.apocalypse = this == ABANDONED || this == WASTELAND;
			s.signage = this == LIVING ? Signage.FULL : this == ABANDONED || this == WASTELAND ? Signage.NONE : Signage.BASIC;
			s.caves = false;
			s.zombies = switch (this) {
				case ABANDONED -> ZombieSettings.preset(ZombieSettings.Difficulty.NORMAL);
				case WASTELAND -> ZombieSettings.preset(ZombieSettings.Difficulty.HARD);
				default -> ZombieSettings.preset(ZombieSettings.Difficulty.OFF);
			};
			s.profile = this;
		}
	}

	public String worldName = "";
	public String place = "";
	/** The saves folder the world lives in; for an extension, the existing one. */
	public String folder = "";
	/** True when the area is added to an existing world. */
	public boolean extend;

	public Profile profile = Profile.CITY;
	public Mode mode = Mode.CITY;
	public double scale = 1.0;
	public double heightMultiplier = 1.0;
	public boolean interiors = false;
	public boolean winter = false;
	public boolean apocalypse = false;
	public Signage signage = Signage.BASIC;
	public boolean caves = false;
	public boolean flatSurroundings = true;
	public ZombieSettings zombies = ZombieSettings.preset(ZombieSettings.Difficulty.OFF);
	public Load load = Load.BALANCED;
	public Double spawnLat;
	public Double spawnLng;

	public GenerationSettings copy() {
		GenerationSettings c = new GenerationSettings();
		c.worldName = worldName;
		c.place = place;
		c.folder = folder;
		c.extend = extend;
		c.profile = profile;
		c.mode = mode;
		c.scale = scale;
		c.heightMultiplier = heightMultiplier;
		c.interiors = interiors;
		c.winter = winter;
		c.apocalypse = apocalypse;
		c.signage = signage;
		c.caves = caves;
		c.flatSurroundings = flatSurroundings;
		c.zombies = zombies.copy();
		c.load = load;
		c.spawnLat = spawnLat;
		c.spawnLng = spawnLng;
		return c;
	}

	/** Blocks along one side, as Arnis will build it (1 block is 1 metre at scale 1). */
	public long blocks(double meters) {
		return Math.round(meters * scale);
	}

	/**
	 * Every world is an Arnis "One World": its frame is pinned to real coordinates, so later
	 * areas can be added to it and line up with what is already there.
	 */
	public List<String> toArgs(GeoBox box, Path savesDir) {
		List<String> args = new ArrayList<>();
		add(args, "--bbox", box.toArnis());
		add(args, "--output-dir", savesDir.toString());
		args.add("--one-world");
		add(args, "--world-name", folder);
		add(args, "--scale", String.format(Locale.ROOT, "%.2f", scale));
		add(args, "--mode", mode.arg);
		add(args, "--height-multiplier", String.format(Locale.ROOT, "%.2f", heightMultiplier));
		add(args, "--interior", Boolean.toString(interiors));
		add(args, "--winter", Boolean.toString(winter));
		add(args, "--post-apocalyptic", Boolean.toString(apocalypse));
		add(args, "--signage", signage.name().toLowerCase(Locale.ROOT));
		add(args, "--world-type", flatSurroundings ? "flat" : "void");
		if (caves) {
			args.add("--caves");
		}
		// No --traffic-lights: Arnis can't add its data pack to a One World, which every Real World is.
		args.add("--map-preview");
		if (spawnLat != null && spawnLng != null && box.contains(spawnLat, spawnLng)) {
			add(args, "--spawn-lat", String.format(Locale.ROOT, "%.6f", spawnLat));
			add(args, "--spawn-lng", String.format(Locale.ROOT, "%.6f", spawnLng));
		}
		return args;
	}

	private static void add(List<String> args, String flag, String value) {
		args.add(flag);
		args.add(value);
	}
}
