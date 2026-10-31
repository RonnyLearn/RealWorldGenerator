package net.ronnylearn.realworld.geo;

/**
 * Web Mercator in normalised units: the whole world is the square 0..1 on both axes,
 * x growing east and y growing south, as in slippy-map tiles.
 */
public final class WebMercator {
	public static final double MAX_LAT = 85.05112878;
	private static final double EARTH_CIRCUMFERENCE = 40_075_016.686;

	private WebMercator() {
	}

	public static double x(double lng) {
		return (lng + 180.0) / 360.0;
	}

	public static double y(double lat) {
		double clamped = Math.max(-MAX_LAT, Math.min(MAX_LAT, lat));
		double sin = Math.sin(Math.toRadians(clamped));
		return 0.5 - Math.log((1 + sin) / (1 - sin)) / (4 * Math.PI);
	}

	public static double lng(double x) {
		return x * 360.0 - 180.0;
	}

	public static double lat(double y) {
		double n = Math.PI - 2 * Math.PI * y;
		return Math.toDegrees(Math.atan(Math.sinh(n)));
	}

	/** Ground metres covered by one normalised unit at a latitude. */
	public static double metersPerUnit(double lat) {
		return EARTH_CIRCUMFERENCE * Math.cos(Math.toRadians(lat));
	}
}
