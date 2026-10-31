package net.ronnylearn.realworld.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.ronnylearn.realworld.RealWorldMod;

/**
 * How the player wants to enter a world: where, in which game mode, at what time and
 * weather, and whether the camera flies in. Armed just before the world opens and
 * applied once, on the integrated server, when the player joins.
 */
public final class EntryPlan {
	public enum Place { SPAWN, LAST, POINT }

	public enum Mode {
		KEEP(null), CREATIVE("creative"), SURVIVAL("survival"), ADVENTURE("adventure"), SPECTATOR("spectator");

		public final String command;

		Mode(String command) {
			this.command = command;
		}
	}

	public enum Time {
		KEEP(-1), SUNRISE(23_200), MORNING(1_000), NOON(6_000), SUNSET(12_500), NIGHT(18_000), LOCAL(-2);

		public final long ticks;

		Time(long ticks) {
			this.ticks = ticks;
		}
	}

	public enum Weather {
		KEEP(null), CLEAR("clear"), RAIN("rain"), THUNDER("thunder");

		public final String command;

		Weather(String command) {
			this.command = command;
		}
	}

	private static EntryPlan pending;
	/**
	 * How long an armed plan waits for the world to open. A big city can take minutes (its
	 * chunks are converted first); a plan dropped too early lost the chosen point and the preload.
	 */
	private static final long ARMED_FOR = 30 * 60_000L;

	public final String worldFolder;
	public Place place = Place.SPAWN;
	/** Block x/z for POINT, or the world spawn for SPAWN. */
	public int pointX, pointZ;
	public boolean hasPoint;
	public Mode mode = Mode.KEEP;
	public Time time = Time.KEEP;
	public Weather weather = Weather.KEEP;
	public boolean cinematic = true;
	/** Chunks prepared before the dark card lifts. */
	public ChunkPreloader.Range preload = ChunkPreloader.Range.NEAR;
	/** Longitude of the area, for the "local solar time" option. */
	public double longitude;
	public boolean firstVisit;
	/** Shown on the dark title card while the world opens. */
	public String title = "";
	public String subtitle = "";
	private long armedAt;

	public EntryPlan(String worldFolder) {
		this.worldFolder = worldFolder;
	}

	public void arm() {
		armedAt = System.currentTimeMillis();
		pending = this;
		CinematicEntry.prepare(this);
	}

	/** Whether the mod is opening one of its worlds right now (armed, not yet joined). */
	public static boolean opening() {
		EntryPlan plan = pending;
		return plan != null && System.currentTimeMillis() - plan.armedAt < ARMED_FOR;
	}

	/** The world did not open (back at the menus): the plan is void. */
	public static void cancel() {
		pending = null;
	}

	/** Called on the server thread when a player joins the integrated server. */
	public static void onJoin(ServerPlayer player, MinecraftServer server) {
		EntryPlan plan = pending;
		pending = null;
		// The plan belongs to the world opened right after it was armed.
		if (plan == null || System.currentTimeMillis() - plan.armedAt > ARMED_FOR) {
			return;
		}
		String world = server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize().getFileName().toString();
		if (!world.equals(plan.worldFolder)) {
			return;
		}
		plan.apply(player, server);
	}

	private void apply(ServerPlayer player, MinecraftServer server) {
		String target = player.getStringUUID();
		if (mode.command != null) {
			run(server, "gamemode " + mode.command + " " + target);
		}
		long ticks = time == Time.LOCAL ? localSolarTicks() : time.ticks;
		if (ticks >= 0) {
			run(server, "time set " + ticks);
		}
		if (weather.command != null) {
			run(server, "weather " + weather.command);
		}
		// A first visit already starts at the spawn; later visits resume where the player left unless told otherwise.
		if (place == Place.POINT || (place == Place.SPAWN && hasPoint && !firstVisit)) {
			ServerLevel level = server.overworld();
			int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pointX, pointZ);
			if (y <= level.getMinY()) {
				y = player.blockPosition().getY();
			}
			moveTo = new double[] {pointX + 0.5, y + 0.1, pointZ + 0.5};
			mover = player;
			moveUntil = server.getTickCount() + 60;
			teleport(player, level);
			RealWorldMod.LOGGER.info("Entering at the chosen point {} {} {}; player now at {}", pointX, y, pointZ, player.blockPosition());
		}
		ChunkPreloader.start(server, server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).normalize(),
			preload, player.getBlockX(), player.getBlockZ());
	}

	/** Where the player was sent on joining, held for a few seconds in case the join moves them back. */
	private static double[] moveTo;
	private static ServerPlayer mover;
	private static int moveUntil;

	private static void teleport(ServerPlayer player, ServerLevel level) {
		player.teleportTo(level, moveTo[0], moveTo[1], moveTo[2], java.util.Set.of(), player.getYRot(), player.getXRot(), true);
	}

	/** Server tick: puts the player back on the chosen point should anything have moved them. */
	public static void tick(MinecraftServer server) {
		if (moveTo == null) {
			return;
		}
		if (server.getTickCount() > moveUntil || mover == null || mover.isRemoved()) {
			moveTo = null;
			mover = null;
			return;
		}
		double dx = mover.getX() - moveTo[0], dz = mover.getZ() - moveTo[2];
		if (dx * dx + dz * dz > 4) {
			RealWorldMod.LOGGER.info("Player was moved to {} after entering; back to the chosen point", mover.blockPosition());
			teleport(mover, server.overworld());
		}
	}

	/** Minecraft time matching the sun at the area's longitude right now (noon = 6000). */
	private long localSolarTicks() {
		double utcHours = (System.currentTimeMillis() / 3_600_000.0) % 24.0;
		double solarHours = ((utcHours + longitude / 15.0) % 24.0 + 24.0) % 24.0;
		// Minecraft day starts at 06:00 (tick 0).
		return (long) (((solarHours - 6.0 + 24.0) % 24.0) * 1000.0);
	}

	private static void run(MinecraftServer server, String command) {
		try {
			server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), command);
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("Entry command failed: {} ({})", command, e.toString());
		}
	}
}
