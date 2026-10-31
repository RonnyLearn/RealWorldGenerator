package net.ronnylearn.realworld.map;

/**
 * Raster map styles the selection map can show; none needs an API key. Each keeps the
 * attribution its provider requires. The area picture for the world is always drawn
 * from the standard style, whose colours the generation animation reads.
 */
public enum TileStyle {
	STANDARD("standard", "https://tile.openstreetmap.org/{z}/{x}/{y}.png", 19, false,
		"© OpenStreetMap contributors"),
	TOPO("topo", "https://tile.opentopomap.org/{z}/{x}/{y}.png", 17, false,
		"© OpenStreetMap contributors, SRTM | © OpenTopoMap (CC-BY-SA)"),
	SATELLITE("satellite", "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}", 19, true,
		"Imagery © Esri, Maxar, Earthstar Geographics");

	public final String id;
	private final String url;
	public final int maxZoom;
	/** The provider serves JPEG, which the game's image loader cannot read; tiles are re-encoded. */
	public final boolean jpeg;
	public final String attribution;

	TileStyle(String id, String url, int maxZoom, boolean jpeg, String attribution) {
		this.id = id;
		this.url = url;
		this.maxZoom = maxZoom;
		this.jpeg = jpeg;
		this.attribution = attribution;
	}

	public String url(int z, int x, int y) {
		return url.replace("{z}", Integer.toString(z)).replace("{x}", Integer.toString(x)).replace("{y}", Integer.toString(y));
	}

	public TileStyle next() {
		return values()[(ordinal() + 1) % values().length];
	}

	public static TileStyle byId(String id) {
		for (TileStyle style : values()) {
			if (style.id.equals(id)) {
				return style;
			}
		}
		return STANDARD;
	}
}
