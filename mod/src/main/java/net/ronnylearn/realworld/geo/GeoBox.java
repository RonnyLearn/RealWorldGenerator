package net.ronnylearn.realworld.geo;

import java.util.Locale;

/** A latitude/longitude rectangle on Earth. */
public record GeoBox(double minLat, double minLng, double maxLat, double maxLng) {
	private static final double EARTH_RADIUS = 6_371_008.8;

	public static GeoBox of(double lat1, double lng1, double lat2, double lng2) {
		return new GeoBox(Math.min(lat1, lat2), Math.min(lng1, lng2), Math.max(lat1, lat2), Math.max(lng1, lng2));
	}

	/** A square of the given side around a point. */
	public static GeoBox around(double lat, double lng, double sideMeters) {
		double dLat = Math.toDegrees(sideMeters / 2 / EARTH_RADIUS);
		double dLng = dLat / Math.max(0.01, Math.cos(Math.toRadians(lat)));
		return of(lat - dLat, lng - dLng, lat + dLat, lng + dLng);
	}

	public double centerLat() {
		return (minLat + maxLat) / 2;
	}

	public double centerLng() {
		return (minLng + maxLng) / 2;
	}

	public double widthMeters() {
		return Math.toRadians(maxLng - minLng) * EARTH_RADIUS * Math.cos(Math.toRadians(centerLat()));
	}

	public double heightMeters() {
		return Math.toRadians(maxLat - minLat) * EARTH_RADIUS;
	}

	public double areaKm2() {
		return widthMeters() * heightMeters() / 1_000_000;
	}

	public boolean contains(double lat, double lng) {
		return lat >= minLat && lat <= maxLat && lng >= minLng && lng <= maxLng;
	}

	/** The form Arnis's --bbox takes: min_lat,min_lng,max_lat,max_lng. */
	public String toArnis() {
		return String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", minLat, minLng, maxLat, maxLng);
	}
}
