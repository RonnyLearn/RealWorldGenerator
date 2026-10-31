package net.ronnylearn.realworld.arnis;

import net.ronnylearn.realworld.geo.GeoBox;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A big area is generated piece by piece: every piece is a run of Arnis of its own, added to
 * the same One World, so only one piece's data is ever in memory. Arnis keeps geometry past
 * each piece's edge, so buildings on a seam come out whole.
 */
public final class Pieces {
	/** Rough memory a run of Arnis needs for an area (km² at world scale), in GB. */
	public static double memoryGb(double effectiveKm2) {
		return 1 + effectiveKm2 * 0.03;
	}

	private static double freeGb = -1;
	private static long freeReadAt;

	/** Memory free right now, in GB (read at most once a second; the map asks every frame). */
	public static synchronized double freeGb() {
		long now = System.currentTimeMillis();
		if (freeGb < 0 || now - freeReadAt > 1000) {
			freeReadAt = now;
			try {
				var os = (com.sun.management.OperatingSystemMXBean) java.lang.management.ManagementFactory.getOperatingSystemMXBean();
				freeGb = os.getFreeMemorySize() / 1e9;
			} catch (Throwable t) {
				freeGb = 4;
			}
		}
		return freeGb;
	}

	/** For the self-test: a fixed piece size in km² at world scale, or 0. */
	public static double testPieceKm2;

	/**
	 * The largest piece (km² at world scale) that fits the load: its share of the memory free
	 * now, and never more than the load's own cap.
	 */
	public static double pieceKm2(GenerationSettings.Load load) {
		if (testPieceKm2 > 0) {
			return testPieceKm2;
		}
		double fits = (freeGb() * load.ramShare - 1) / 0.03;
		return Math.max(0.5, Math.min(load.maxPieceKm2, fits));
	}

	/**
	 * Cuts the box into a grid of near-square pieces of at most {@code pieceKm2} each (at world
	 * scale), nearest to {@code (lat, lng)} first, so the place the player starts is built first.
	 */
	public static List<GeoBox> split(GeoBox box, double scale, double pieceKm2, double lat, double lng) {
		double side = Math.sqrt(pieceKm2) * 1000 / scale;
		int cols = Math.max(1, (int) Math.ceil(box.widthMeters() / side - 1e-6));
		int rows = Math.max(1, (int) Math.ceil(box.heightMeters() / side - 1e-6));
		List<GeoBox> pieces = new ArrayList<>(cols * rows);
		for (int r = 0; r < rows; r++) {
			for (int c = 0; c < cols; c++) {
				double lat0 = box.minLat() + (box.maxLat() - box.minLat()) * r / rows;
				double lat1 = box.minLat() + (box.maxLat() - box.minLat()) * (r + 1) / rows;
				double lng0 = box.minLng() + (box.maxLng() - box.minLng()) * c / cols;
				double lng1 = box.minLng() + (box.maxLng() - box.minLng()) * (c + 1) / cols;
				pieces.add(new GeoBox(lat0, lng0, lat1, lng1));
			}
		}
		pieces.sort(Comparator.comparingDouble(p -> {
			double dLat = p.centerLat() - lat, dLng = (p.centerLng() - lng) * Math.cos(Math.toRadians(lat));
			return dLat * dLat + dLng * dLng;
		}));
		return pieces;
	}

	/** How many pieces the box will be cut into. */
	public static int count(GeoBox box, double scale, GenerationSettings.Load load) {
		double side = Math.sqrt(pieceKm2(load)) * 1000 / scale;
		return Math.max(1, (int) Math.ceil(box.widthMeters() / side - 1e-6)) * Math.max(1, (int) Math.ceil(box.heightMeters() / side - 1e-6));
	}

	private Pieces() {
	}
}
