package net.ronnylearn.realworld.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.net.Http;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Place search through OpenStreetMap's Nominatim. Its policy allows at most one request
 * a second and no search-as-you-type, so the map screen searches on Enter only.
 */
public final class Geocoder {
	private static final Object LOCK = new Object();
	private static long lastRequest;

	public record Place(String name, String detail, double lat, double lng, GeoBox bounds) {
	}

	private Geocoder() {
	}

	public static CompletableFuture<List<Place>> search(String query, String language) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				synchronized (LOCK) {
					long wait = 1100 - (System.currentTimeMillis() - lastRequest);
					if (wait > 0) {
						Thread.sleep(wait);
					}
					lastRequest = System.currentTimeMillis();
				}
				String url = "https://nominatim.openstreetmap.org/search?format=jsonv2&limit=7&q="
					+ URLEncoder.encode(query, StandardCharsets.UTF_8);
				String body = new String(Http.get(url, language), StandardCharsets.UTF_8);
				return parse(JsonParser.parseString(body).getAsJsonArray());
			} catch (Exception e) {
				throw new RuntimeException(e.getMessage(), e);
			}
		});
	}

	/** A short human name for a point: district and city, as far as OpenStreetMap knows them. */
	public static CompletableFuture<String> reverse(double lat, double lng, String language) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				synchronized (LOCK) {
					long wait = 1100 - (System.currentTimeMillis() - lastRequest);
					if (wait > 0) {
						Thread.sleep(wait);
					}
					lastRequest = System.currentTimeMillis();
				}
				String url = String.format(java.util.Locale.ROOT,
					"https://nominatim.openstreetmap.org/reverse?format=jsonv2&zoom=14&lat=%.6f&lon=%.6f", lat, lng);
				JsonObject o = JsonParser.parseString(new String(Http.get(url, language), StandardCharsets.UTF_8)).getAsJsonObject();
				if (!o.has("address")) {
					return "";
				}
				JsonObject a = o.getAsJsonObject("address");
				// "District, City": the nearest named area, then the settlement it belongs to.
				String local = first(a, "suburb", "quarter", "neighbourhood", "city_district", "hamlet");
				String city = first(a, "city", "town", "village", "municipality", "county", "state", "country");
				if (local.isEmpty() || local.equals(city)) {
					return city;
				}
				return city.isEmpty() ? local : local + ", " + city;
			} catch (Exception e) {
				return "";
			}
		});
	}

	/** The first of the address fields that holds a real name (house-number-like values are skipped). */
	private static String first(JsonObject address, String... keys) {
		for (String key : keys) {
			if (address.has(key)) {
				String value = address.get(key).getAsString().trim();
				if (!value.isEmpty() && !value.chars().allMatch(c -> Character.isDigit(c) || c == ' ' || c == '-')) {
					return value;
				}
			}
		}
		return "";
	}

	private static List<Place> parse(JsonArray array) {
		List<Place> places = new ArrayList<>();
		for (JsonElement element : array) {
			JsonObject o = element.getAsJsonObject();
			String full = o.get("display_name").getAsString();
			int comma = full.indexOf(',');
			String name = o.has("name") && !o.get("name").getAsString().isBlank()
				? o.get("name").getAsString()
				: comma > 0 ? full.substring(0, comma) : full;
			String detail = comma > 0 ? full.substring(comma + 1).trim() : "";
			double lat = o.get("lat").getAsDouble();
			double lng = o.get("lon").getAsDouble();
			GeoBox bounds = null;
			if (o.has("boundingbox")) {
				JsonArray b = o.getAsJsonArray("boundingbox");
				bounds = GeoBox.of(b.get(0).getAsDouble(), b.get(2).getAsDouble(), b.get(1).getAsDouble(), b.get(3).getAsDouble());
			}
			places.add(new Place(name, detail, lat, lng, bounds));
		}
		return places;
	}
}
