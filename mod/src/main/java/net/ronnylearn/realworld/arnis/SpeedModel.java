package net.ronnylearn.realworld.arnis;

import net.minecraft.network.chat.Component;
import net.ronnylearn.realworld.RealWorldConfig;
import net.ronnylearn.realworld.RealWorldMod;
import net.ronnylearn.realworld.world.WorldLibrary;
import net.ronnylearn.realworld.world.WorldMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * How long a generation will take on this computer. Every finished generation is remembered
 * (area at world scale, seconds, pieces, share of the cores); the estimate is a fixed cost per
 * piece (downloads, setup) plus the area over the speed those runs showed. With no runs yet,
 * the worlds already in the library teach it, and without those a short processor test.
 * What is in the area matters too (water and fields are quick, dense streets slow), so the
 * answer is a range.
 */
public final class SpeedModel {
	/** Seconds every piece costs whatever its size. */
	private static final double PIECE_COST = 4;
	/** Speed (km² at world scale per second, all cores) of the reference processor below. */
	private static final double BASE_RATE = 0.6;
	/** Processor test score of the computer the base rate was measured on. */
	private static final double BASE_SCORE = 1000; // i5-12400F, 12 threads
	private static final int KEEP = 16;

	private static volatile double cpuScore = -1;
	/** Keeps the test's result alive, so the work cannot be optimised away. */
	private static volatile long cpuSink;
	private static boolean seeded;

	private SpeedModel() {
	}

	/**
	 * Measures the processor once, in the background: every core mixes numbers for a fixed
	 * count; the score is millions of steps a second. A quarter of a second, low priority.
	 */
	public static void benchmarkAsync() {
		Thread t = new Thread(() -> {
			int cores = Runtime.getRuntime().availableProcessors();
			long steps = 30_000_000L;
			long start = System.nanoTime();
			long sink = IntStream.range(0, cores).parallel().mapToLong(i -> {
				long x = 0x9E3779B97F4A7C15L + i;
				for (long k = 0; k < steps; k++) {
					x ^= x << 13;
					x ^= x >>> 7;
					x ^= x << 17;
				}
				return x;
			}).sum();
			double seconds = (System.nanoTime() - start) / 1e9;
			cpuSink = sink;
			cpuScore = cores * steps / seconds / 1e6;
			RealWorldMod.LOGGER.info("Processor test: score {} ({} threads, {} ms)", Math.round(cpuScore), cores, Math.round(seconds * 1000));
		}, "RealWorld-CpuTest");
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/** Remembers a finished generation. */
	public static void record(double effectiveKm2, double seconds, int pieces, GenerationSettings.Load load) {
		if (effectiveKm2 <= 0 || seconds <= 0) {
			return;
		}
		RealWorldConfig config = RealWorldConfig.get();
		List<double[]> list = new ArrayList<>(config.speed == null ? List.of() : config.speed);
		list.add(new double[] {effectiveKm2, seconds, Math.max(1, pieces), share(load)});
		while (list.size() > KEEP) {
			list.removeFirst();
		}
		config.speed = list;
		config.save();
	}

	/** Seconds the area will take: {quick, likely, slow}. */
	public static double[] estimate(double effectiveKm2, int pieces, GenerationSettings.Load load) {
		double[] rates = rates();
		double s = share(load);
		double fixed = PIECE_COST * Math.max(1, pieces);
		return new double[] {
			fixed + effectiveKm2 / (rates[2] * s),
			fixed + effectiveKm2 / (rates[1] * s),
			fixed + effectiveKm2 / (rates[0] * s)
		};
	}

	/** Like "≈ 2–5 min", or a single value when the range is narrow. */
	public static String format(double[] seconds) {
		String low = duration(seconds[0]), high = duration(seconds[2]);
		return "≈ " + (low.equals(high) ? low : low + " – " + high);
	}

	private static String duration(double seconds) {
		if (seconds < 90) {
			return Component.translatable("realworld.time.seconds", Math.max(5, Math.round(seconds / 5) * 5)).getString();
		}
		if (seconds < 90 * 60) {
			return Component.translatable("realworld.time.minutes", Math.round(seconds / 60)).getString();
		}
		return Component.translatable("realworld.time.hours", Math.round(seconds / 360) / 10.0).getString();
	}

	/** How the work scales with the load's share of the cores (threads never add up fully). */
	private static double share(GenerationSettings.Load load) {
		return Math.pow(load.cpu, 0.8);
	}

	/** Speeds at full share, km²/s: {slow quartile, median, fast quartile}. */
	private static double[] rates() {
		seed();
		List<double[]> samples = RealWorldConfig.get().speed;
		if (samples == null || samples.isEmpty()) {
			double score = cpuScore > 0 ? cpuScore : BASE_SCORE;
			double r = BASE_RATE * score / BASE_SCORE;
			return new double[] {r * 0.4, r, r * 2.5};
		}
		double[] r = samples.stream()
			.mapToDouble(x -> x[0] / Math.max(0.5, x[1] - PIECE_COST * x[2]) / Math.max(0.05, x[3]))
			.sorted().toArray();
		double low = quantile(r, 0.25), mid = quantile(r, 0.5), high = quantile(r, 0.75);
		// With few runs the spread says little: keep the range at least this wide.
		return new double[] {Math.min(low, mid * 0.6), mid, Math.max(high, mid * 1.6)};
	}

	private static double quantile(double[] sorted, double q) {
		double pos = q * (sorted.length - 1);
		int i = (int) Math.floor(pos);
		int j = Math.min(sorted.length - 1, i + 1);
		return sorted[i] + (sorted[j] - sorted[i]) * (pos - i);
	}

	/**
	 * The first time, the worlds already generated teach the model: their size and how long
	 * they took are in their meta. They ran at the default load.
	 */
	private static void seed() {
		if (seeded) {
			return;
		}
		seeded = true;
		RealWorldConfig config = RealWorldConfig.get();
		if (config.speed != null && !config.speed.isEmpty()) {
			return;
		}
		List<double[]> list = new ArrayList<>();
		try {
			for (WorldLibrary.Entry e : WorldLibrary.scan()) {
				WorldMeta m = e.meta;
				if (m.generationSeconds <= 0 || m.maxLat <= m.minLat) {
					continue;
				}
				double eff = m.bounds().areaKm2() * m.scale * m.scale;
				int pieces = Math.max(1, (int) Math.ceil(eff / GenerationSettings.Load.BALANCED.maxPieceKm2));
				list.add(new double[] {eff, m.generationSeconds, pieces, share(GenerationSettings.Load.BALANCED)});
			}
		} catch (Exception ignored) {
		}
		list.sort((a, b) -> Double.compare(a[1], b[1]));
		if (list.size() > KEEP) {
			list = new ArrayList<>(list.subList(list.size() - KEEP, list.size()));
		}
		if (!list.isEmpty()) {
			config.speed = list;
			config.save();
			RealWorldMod.LOGGER.info("Generation speed learned from {} existing worlds", list.size());
		}
	}

	/** For the self-test and the log: the median speed in use, km²/s at full share. */
	public static double medianRate() {
		return rates()[1];
	}

}
