package net.ronnylearn.realworld.world;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.ronnylearn.realworld.RealWorldMod;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Prepares a world's chunks before the player sees them. Arnis writes chunks in an older
 * format without light, so the game would convert and light each one the first time it
 * loads, which is what makes walking into new streets stutter. Here every chunk of the chosen
 * radius is loaded to full status once, behind the dark entry card, and marked for saving:
 * it goes back to disk converted and lit, and loads quickly from then on. Chunks already
 * prepared are remembered in the world's meta and skipped.
 */
public final class ChunkPreloader {
	/** How far around the player to prepare. */
	public enum Range {
		OFF(0), NEAR(-1), FAR(48), ALL(-2);

		/** Radius in chunks; -1 is the view distance, -2 the whole area. */
		public final int radius;

		Range(int radius) {
			this.radius = radius;
		}
	}

	/** Chunks being loaded at once; more only queue up inside the game. */
	private static final int IN_FLIGHT = 64;
	/** Prepared rectangles kept in the meta before they are merged into their bounds. */
	private static final int MAX_RECTS = 64;

	private static final ArrayDeque<ChunkPos> queue = new ArrayDeque<>();
	private static final AtomicInteger inFlight = new AtomicInteger();
	private static final AtomicInteger done = new AtomicInteger();
	private static volatile int total;
	private static volatile boolean running;
	private static volatile boolean stopRequested;
	private static ServerLevel level;
	private static Path world;
	private static int[] rect;
	private static long startedAt;

	private ChunkPreloader() {
	}

	/** True while chunks are being prepared. */
	public static boolean running() {
		return running;
	}

	/** 0 to 1. */
	public static float progress() {
		int t = total;
		return t == 0 ? 1f : Math.min(1f, done.get() / (float) t);
	}

	public static int done() {
		return done.get();
	}

	public static int total() {
		return total;
	}

	/** Stops after the chunks already loading; what is done stays done. */
	public static void stop() {
		stopRequested = true;
	}

	/** Chunks a range covers around a block position, clipped to the generated area; -1 when unknown. */
	public static int[] chunkRect(Range range, int[] area, int blockX, int blockZ, int viewDistance) {
		if (range == Range.OFF || area == null) {
			return null;
		}
		int minX = area[0] >> 4, minZ = area[1] >> 4, maxX = area[2] >> 4, maxZ = area[3] >> 4;
		if (range == Range.ALL) {
			return new int[] {minX, minZ, maxX, maxZ};
		}
		int r = range == Range.NEAR ? viewDistance + 2 : range.radius;
		int cx = blockX >> 4, cz = blockZ >> 4;
		int[] out = {Math.max(minX, cx - r), Math.max(minZ, cz - r), Math.min(maxX, cx + r), Math.min(maxZ, cz + r)};
		return out[0] > out[2] || out[1] > out[3] ? null : out;
	}

	/** Starts preparing a world's chunks around a block position. Server thread. */
	public static void start(MinecraftServer server, Path worldDir, Range range, int blockX, int blockZ) {
		queue.clear();
		done.set(0);
		total = 0;
		stopRequested = false;
		WorldMeta meta = WorldMeta.read(worldDir);
		if (meta == null) {
			return;
		}
		int[] r = chunkRect(range, meta.area, blockX, blockZ, server.getPlayerList().getViewDistance());
		if (r == null) {
			return;
		}
		List<int[]> prepared = meta.preloaded == null ? List.of() : meta.preloaded;
		int cx = blockX >> 4, cz = blockZ >> 4;
		List<ChunkPos> chunks = new ArrayList<>();
		for (int z = r[1]; z <= r[3]; z++) {
			for (int x = r[0]; x <= r[2]; x++) {
				if (!covered(prepared, x, z)) {
					chunks.add(new ChunkPos(x, z));
				}
			}
		}
		if (chunks.isEmpty()) {
			return;
		}
		// Nearest first: the player's surroundings are ready soonest.
		chunks.sort(Comparator.comparingLong(p -> (long) (p.x() - cx) * (p.x() - cx) + (long) (p.z() - cz) * (p.z() - cz)));
		queue.addAll(chunks);
		total = chunks.size();
		level = server.overworld();
		world = worldDir;
		rect = r;
		startedAt = System.nanoTime();
		running = true;
		RealWorldMod.LOGGER.info("Preparing {} chunks ({}) of {}", total, range, worldDir.getFileName());
	}

	private static boolean covered(List<int[]> rects, int x, int z) {
		for (int[] r : rects) {
			if (x >= r[0] && x <= r[2] && z >= r[1] && z <= r[3]) {
				return true;
			}
		}
		return false;
	}

	/** Server tick: keeps a fixed number of chunks loading until all are prepared. */
	public static void tick(MinecraftServer server) {
		if (!running) {
			return;
		}
		if (level == null || level.getServer() != server) {
			running = false;
			return;
		}
		while (!stopRequested && inFlight.get() < IN_FLIGHT && !queue.isEmpty()) {
			ChunkPos pos = queue.poll();
			inFlight.incrementAndGet();
			level.getChunkSource().getChunkFuture(pos.x(), pos.z(), ChunkStatus.FULL, true).whenComplete((result, error) -> {
				// Saved again on unload, now converted and lit.
				if (result != null) {
					result.ifSuccess(chunk -> chunk.markUnsaved());
				}
				inFlight.decrementAndGet();
				done.incrementAndGet();
			});
		}
		if (inFlight.get() == 0 && (queue.isEmpty() || stopRequested)) {
			finish();
		}
	}

	private static void finish() {
		boolean complete = queue.isEmpty() && !stopRequested;
		RealWorldMod.LOGGER.info("Prepared {} of {} chunks in {} s", done.get(), total, (System.nanoTime() - startedAt) / 1_000_000_000L);
		queue.clear();
		running = false;
		if (!complete || world == null) {
			return;
		}
		try {
			WorldMeta meta = WorldMeta.read(world);
			if (meta != null) {
				if (meta.preloaded == null) {
					meta.preloaded = new ArrayList<>();
				}
				meta.preloaded.add(rect);
				if (meta.preloaded.size() > MAX_RECTS) {
					// Too many pieces to keep: only the latest ones count, the rest is prepared again if needed.
					meta.preloaded = new ArrayList<>(meta.preloaded.subList(meta.preloaded.size() - MAX_RECTS, meta.preloaded.size()));
				}
				meta.write(world);
			}
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("Could not record prepared chunks: {}", e.toString());
		}
	}

	/** The server is going away (world closed): forget everything. */
	public static void reset() {
		queue.clear();
		running = false;
		level = null;
		world = null;
	}
}
