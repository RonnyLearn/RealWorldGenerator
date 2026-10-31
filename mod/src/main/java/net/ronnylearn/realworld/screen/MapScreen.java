package net.ronnylearn.realworld.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.ronnylearn.realworld.RealWorldConfig;
import net.ronnylearn.realworld.arnis.GenerationSettings;
import net.ronnylearn.realworld.arnis.GenerationTask;
import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.geo.WebMercator;
import net.ronnylearn.realworld.map.Geocoder;
import net.ronnylearn.realworld.map.MapView;
import net.ronnylearn.realworld.map.TileCache;
import net.ronnylearn.realworld.map.TileStyle;
import net.ronnylearn.realworld.ui.Ui;
import net.ronnylearn.realworld.world.WorldLibrary;
import net.ronnylearn.realworld.world.WorldMeta;
import net.ronnylearn.realworld.world.WorldPicture;
import net.ronnylearn.realworld.world.ZombieSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Pick the place: a map to find and frame the area, and a sidebar with the world's options. */
public class MapScreen extends Screen {
	private int sidebar = 186;
	private static final double MIN_SIDE = 40;
	/** Past this the area is allowed, but only after a warning: it takes long and needs a lot of memory. */
	/** The tile archive Arnis falls back to for big boxes serves no more than this. */
	private static final double ARCHIVE_KM2 = 25000;

	private final LibraryScreen library;
	/** The world an area is being added to, or null for a new world. */
	private final WorldLibrary.Entry extending;
	private final RealWorldConfig config = RealWorldConfig.get();
	private final GenerationSettings settings = config.settings();
	private final MapView map = new MapView();
	private boolean viewSet;

	private EditBox search;
	private EditBox name;
	private Button create;
	private Button panTool, selectTool, spawnTool;
	private final List<Geocoder.Place> results = new ArrayList<>();
	private Component searchStatus;
	private boolean nameEdited;
	private String place = "";
	private long selectionChangedAt;
	private boolean placeRequested = true;
	private int placeRequest;

	public MapScreen(LibraryScreen library) {
		this(library, null);
	}

	/** Opens the map to add an area to an existing world (null: a new world). */
	public MapScreen(LibraryScreen library, WorldLibrary.Entry extending) {
		super(Component.translatable("realworld.map.title"));
		this.library = library;
		this.extending = extending;
		if (extending != null) {
			// An added area must match how the world was built; the rest may change.
			WorldMeta m = extending.meta;
			settings.extend = true;
			settings.folder = extending.folder;
			settings.worldName = m.name;
			settings.place = m.place;
			settings.scale = m.scale;
			settings.mode = parse(GenerationSettings.Mode.class, m.mode, m.terrain ? GenerationSettings.Mode.CITY : GenerationSettings.Mode.FLAT);
			settings.heightMultiplier = m.heightMultiplier;
			settings.flatSurroundings = !"void".equals(m.worldType);
			settings.interiors = m.interiors;
			settings.winter = m.winter;
			settings.apocalypse = m.apocalypse;
			settings.profile = parse(GenerationSettings.Profile.class, m.profile, GenerationSettings.Profile.CUSTOM);
			settings.zombies = m.zombies == null ? new ZombieSettings() : m.zombies.copy();
			for (WorldPicture.Area a : WorldPicture.areas(extending.path)) {
				map.existing.add(new GeoBox(a.minLat(), a.minLng(), a.maxLat(), a.maxLng()));
			}
			nameEdited = true;
		}
		map.style = TileStyle.byId(config.style);
		map.scale = settings.scale;
		map.onSelectionChanged = () -> {
			selectionChangedAt = System.currentTimeMillis();
			placeRequested = false;
			updateCreate();
		};
	}

	private int mapW() {
		return width - sidebar;
	}

	@Override
	protected void init() {
		sidebar = Math.max(160, Math.min(186, (int) (width * 0.4)));
		map.x = 0;
		map.y = 0;
		map.w = mapW();
		map.h = height;
		if (!viewSet) {
			viewSet = true;
			if (extending != null && !map.existing.isEmpty()) {
				GeoBox world = extending.meta.bounds();
				map.setView(world.centerLat(), world.centerLng(), 14);
				map.flyToFit(world);
			} else if (!Double.isNaN(config.lat)) {
				map.setView(config.lat, config.lng, config.zoom);
			} else if (minecraft.options.languageCode.startsWith("ru")) {
				map.setView(55.7539, 37.6208, 13);
			} else {
				map.setView(30, 10, 2.5);
			}
		}

		// Top row of the map: search on the left, tools on the right; the map style sits by the zoom buttons.
		int styleW = 62;
		int tx = mapW() - 8 - 3 * 22 + 2;
		search = new EditBox(font, 8, 9, Math.max(80, Math.min(250, tx - 16)), 18, Component.translatable("realworld.map.search"));
		search.setHint(Component.translatable("realworld.map.search.hint").withColor(Ui.FAINT));
		search.setMaxLength(200);
		addRenderableWidget(search);

		panTool = addRenderableWidget(toolButton("✋", "realworld.map.tool.pan", tx, MapView.Tool.PAN));
		selectTool = addRenderableWidget(toolButton("", "realworld.map.tool.select", tx + 22, MapView.Tool.SELECT));
		spawnTool = addRenderableWidget(toolButton("⚑", "realworld.map.tool.spawn", tx + 44, MapView.Tool.SPAWN));
		addRenderableWidget(Button.builder(styleLabel(), b -> {
			map.style = map.style.next();
			config.style = map.style.id;
			b.setMessage(styleLabel());
		}).bounds(mapW() - 8 - styleW, height - 42, styleW, 20).tooltip(Tooltip.create(Component.translatable("realworld.map.style"))).build());

		int zx = mapW() - 28;
		addRenderableWidget(Button.builder(Component.literal("+"), b -> map.zoomBy(1)).bounds(zx, height - 88, 20, 20)
			.tooltip(Tooltip.create(Component.translatable("realworld.map.zoom_in"))).build());
		addRenderableWidget(Button.builder(Component.literal("−"), b -> map.zoomBy(-1)).bounds(zx, height - 66, 20, 20)
			.tooltip(Tooltip.create(Component.translatable("realworld.map.zoom_out"))).build());

		initSidebar();
		updateCreate();
	}

	private Component styleLabel() {
		return Component.translatable("realworld.map.style." + map.style.id);
	}

	private Button toolButton(String symbol, String key, int x, MapView.Tool tool) {
		return Button.builder(Component.literal(symbol), b -> map.tool = tool).bounds(x, 8, 20, 20)
			.tooltip(Tooltip.create(Component.translatable(key))).build();
	}

	private void initSidebar() {
		int sx = width - sidebar + 8, sw = sidebar - 16, half = (sw - 4) / 2;
		int nameY = 69;

		name = new EditBox(font, sx, nameY, sw, 18, Component.translatable("realworld.map.name"));
		name.setHint(Component.translatable("realworld.map.name.hint").withColor(Ui.FAINT));
		name.setMaxLength(64);
		name.setValue(settings.worldName);
		name.setResponder(v -> {
			settings.worldName = v;
			nameEdited = true;
		});
		addRenderableWidget(name);

		name.setEditable(extending == null);

		int y = nameY + 22;
		addRenderableWidget(Button.builder(profileLabel(), b -> openSettings())
			.bounds(sx, y, sw, 20).tooltip(Tooltip.create(Component.translatable("realworld.map.settings.tip"))).build());
		y += 22;
		Button scale = addRenderableWidget(Button.builder(scaleLabel(), b -> {
			int i = 0;
			while (i < GenerationSettings.SCALES.length && GenerationSettings.SCALES[i] <= settings.scale + 1e-6) {
				i++;
			}
			settings.scale = GenerationSettings.SCALES[i % GenerationSettings.SCALES.length];
			map.scale = settings.scale;
			custom();
			b.setMessage(scaleLabel());
			updateCreate();
		}).bounds(sx, y, half, 20).tooltip(Tooltip.create(Component.translatable("realworld.option.scale.tip"))).build());
		scale.active = extending == null;
		addRenderableWidget(toggle("interiors", () -> settings.interiors, v -> settings.interiors = v, sx + half + 4, y, half));
		y += 22;
		addRenderableWidget(toggle("apocalypse", () -> settings.apocalypse, v -> settings.apocalypse = v, sx, y, half));
		addRenderableWidget(Button.builder(zombieLabel(), b -> {
			ZombieSettings.Difficulty[] all = ZombieSettings.Difficulty.values();
			settings.zombies.difficulty = all[(settings.zombies.difficulty.ordinal() + 1) % all.length];
			custom();
			b.setMessage(zombieLabel());
		}).bounds(sx + half + 4, y, half, 20).tooltip(Tooltip.create(Component.translatable("realworld.option.zombies.tip"))).build());
		y += 22;
		addRenderableWidget(Button.builder(loadLabel(), b -> {
			GenerationSettings.Load[] all = GenerationSettings.Load.values();
			settings.load = all[(settings.load.ordinal() + 1) % all.length];
			b.setMessage(loadLabel());
			b.setTooltip(Tooltip.create(Component.translatable("realworld.load." + settings.load.name().toLowerCase(Locale.ROOT) + ".tip")));
			updateCreate();
		}).bounds(sx, y, sw, 20)
			.tooltip(Tooltip.create(Component.translatable("realworld.load." + settings.load.name().toLowerCase(Locale.ROOT) + ".tip"))).build());
		y += 24;

		// Quick squares only where the screen is tall enough to keep them clear of "Create".
		int third = (sw - 8) / 3;
		int[] sides = {500, 1000, 2000};
		for (int i = 0; i < 3; i++) {
			int side = sides[i];
			if (y + 16 > height - 52) {
				break;
			}
			addRenderableWidget(Button.builder(Component.literal(Ui.distance(side)), b -> presetSquare(side))
				.bounds(sx + i * (third + 4), y, third, 16)
				.tooltip(Tooltip.create(Component.translatable("realworld.map.preset.tip", Ui.distance(side)))).build());
		}

		create = addRenderableWidget(Button.builder(Component.translatable(extending == null ? "realworld.map.create" : "realworld.map.create.extend"), b -> startGeneration())
			.bounds(sx, height - 48, sw, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
			.bounds(sx, height - 25, sw, 20).build());
	}

	private interface Getter {
		boolean get();
	}

	private interface Setter {
		void set(boolean value);
	}

	private Button toggle(String key, Getter get, Setter set, int x, int y, int w) {
		return Button.builder(toggleLabel(key, get.get()), b -> {
			set.set(!get.get());
			custom();
			b.setMessage(toggleLabel(key, get.get()));
		}).bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("realworld.option." + key + ".tip"))).build();
	}

	private static Component toggleLabel(String key, boolean on) {
		return Component.translatable("realworld.option." + key).append(" ")
			.append(Component.literal(on ? "✔" : "✘").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
	}

	private Component scaleLabel() {
		String s = settings.scale == Math.rint(settings.scale)
			? String.format(Locale.ROOT, "%.0f", settings.scale)
			: String.format(Locale.ROOT, "%s", settings.scale);
		return Component.translatable("realworld.option.scale", s);
	}

	private Component profileLabel() {
		return Component.translatable("realworld.map.profile").append(": ")
			.append(Component.translatable("realworld.profile." + settings.profile.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.YELLOW))
			.append(" ▸");
	}

	private Component loadLabel() {
		ChatFormatting color = switch (settings.load) {
			case LIGHT -> ChatFormatting.AQUA;
			case BALANCED -> ChatFormatting.GREEN;
			case MAX -> ChatFormatting.GOLD;
		};
		return Component.translatable("realworld.load").append(": ")
			.append(Component.translatable("realworld.load." + settings.load.name().toLowerCase(Locale.ROOT)).withStyle(color));
	}

	private Component zombieLabel() {
		return Component.translatable("realworld.option.zombies").append(": ")
			.append(Component.translatable("realworld.settings.zombies." + settings.zombies.difficulty.name().toLowerCase(Locale.ROOT))
				.withStyle(settings.zombies.enabled() ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
	}

	/** Any manual change leaves the chosen profile behind. */
	private void custom() {
		settings.profile = GenerationSettings.Profile.CUSTOM;
		rebuildWidgets();
	}

	private void openSettings() {
		minecraft.gui.setScreen(new WorldSettingsScreen(this, settings, () -> {
			map.scale = settings.scale;
			updateCreate();
		}));
	}

	private static <E extends Enum<E>> E parse(Class<E> type, String name, E fallback) {
		try {
			return Enum.valueOf(type, name);
		} catch (Exception e) {
			return fallback;
		}
	}

	private void presetSquare(double side) {
		double lat = map.centerLat(), lng = map.centerLng();
		GeoBox box = GeoBox.around(lat, lng, side);
		map.setSelection(box);
		map.flyToFit(box);
	}

	// ---------------------------------------------------------------- state

	private enum Verdict { NONE, TOO_SMALL, QUICK, MEDIUM, LONG, VERY_LONG, LARGE, HUGE, TOO_BIG }

	/** Blocks are generated from the area at world scale; memory and time follow it. */
	private double effectiveKm2() {
		GeoBox box = map.selection();
		return box == null ? 0 : box.areaKm2() * settings.scale * settings.scale;
	}

	/** Pieces the selection will be built in, with the chosen load and the memory free now. */
	private int pieces() {
		GeoBox box = map.selection();
		return box == null ? 1 : net.ronnylearn.realworld.arnis.Pieces.count(box, settings.scale, settings.load);
	}

	/** Seconds the selection will take here: quick, likely, slow. */
	private double[] estimate() {
		return net.ronnylearn.realworld.arnis.SpeedModel.estimate(effectiveKm2(), pieces(), settings.load);
	}

	private Verdict verdict() {
		GeoBox box = map.selection();
		if (box == null) {
			return Verdict.NONE;
		}
		if (box.widthMeters() < MIN_SIDE || box.heightMeters() < MIN_SIDE) {
			return Verdict.TOO_SMALL;
		}
		double effective = effectiveKm2();
		if (effective > ARCHIVE_KM2) return Verdict.TOO_BIG;
		// By the time it will likely take on this computer.
		double likely = estimate()[1];
		if (likely > 30 * 60) return Verdict.HUGE;
		if (likely > 10 * 60) return Verdict.LARGE;
		if (likely > 5 * 60) return Verdict.VERY_LONG;
		if (likely > 60) return Verdict.LONG;
		if (likely > 20) return Verdict.MEDIUM;
		return Verdict.QUICK;
	}

	private void updateCreate() {
		if (create != null) {
			Verdict v = verdict();
			create.active = v != Verdict.NONE && v != Verdict.TOO_SMALL && v != Verdict.TOO_BIG;
		}
	}

	@Override
	public void tick() {
		// Name the place once the selection has settled (Nominatim allows one request a second).
		if (!placeRequested && map.selection() != null && !map.dragging()
			&& System.currentTimeMillis() - selectionChangedAt > 900) {
			placeRequested = true;
			int request = ++placeRequest;
			GeoBox box = map.selection();
			Geocoder.reverse(box.centerLat(), box.centerLng(), minecraft.options.languageCode.replace('_', '-'))
				.thenAccept(found -> minecraft.execute(() -> {
					if (request == placeRequest && !found.isBlank()) {
						place = found;
						if (!nameEdited || name.getValue().isBlank()) {
							String first = found.contains(",") ? found.substring(0, found.indexOf(',')) : found;
							name.setValue(first);
							nameEdited = false;
						}
					}
				}));
		}
		boolean busy = GenerationTask.current() != null && GenerationTask.current().state() == GenerationTask.State.RUNNING;
		if (busy && create.active) {
			create.active = false;
			create.setTooltip(Tooltip.create(Component.translatable("realworld.map.busy")));
		}
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		// The map is the background.
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		map.render(g, font, mouseX, mouseY);
		drawMapOverlay(g);
		drawSidebar(g);
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		highlightTool(g);
		drawResults(g, mouseX, mouseY);
	}

	private void highlightTool(GuiGraphicsExtractor g) {
		Button active = switch (map.tool) {
			case PAN -> panTool;
			case SELECT -> selectTool;
			case SPAWN -> spawnTool;
		};
		g.outline(active.getX() - 1, active.getY() - 1, 22, 22, Ui.ACCENT);
		// The selection tool's icon: a small dashed frame.
		int ix = selectTool.getX() + 5, iy = selectTool.getY() + 6, iw = 10, ih = 8;
		int color = selectTool.isHoveredOrFocused() ? 0xFFFFFFA0 : 0xFFE0E0E0;
		for (int d = 0; d < iw; d += 3) {
			g.fill(ix + d, iy, ix + Math.min(iw, d + 2), iy + 1, color);
			g.fill(ix + d, iy + ih - 1, ix + Math.min(iw, d + 2), iy + ih, color);
		}
		for (int d = 0; d < ih; d += 3) {
			g.fill(ix, iy + d, ix + 1, iy + Math.min(ih, d + 2), color);
			g.fill(ix + iw - 1, iy + d, ix + iw, iy + Math.min(ih, d + 2), color);
		}
	}

	private void drawMapOverlay(GuiGraphicsExtractor g) {
		int mw = mapW();
		// Scale bar.
		double metersPer100 = 0;
		GeoBox probe = probeBox();
		if (probe != null) {
			metersPer100 = probe.widthMeters();
		}
		if (metersPer100 > 0) {
			double target = metersPer100 * 0.8;
			double nice = Math.pow(10, Math.floor(Math.log10(target)));
			for (double m : new double[] {5, 2, 1}) {
				if (nice * m <= target) {
					nice *= m;
					break;
				}
			}
			int len = (int) Math.round(100 * nice / metersPer100);
			int bx = 8, by = height - 22;
			g.fill(bx - 3, by - 11, bx + len + 3, by + 5, 0xB0101418);
			g.fill(bx, by, bx + len, by + 2, Ui.TEXT);
			g.fill(bx, by - 3, bx + 1, by + 2, Ui.TEXT);
			g.fill(bx + len - 1, by - 3, bx + len, by + 2, Ui.TEXT);
			g.text(font, Ui.distance(nice), bx, by - 10, Ui.TEXT, false);
		}

		// Attribution (required by the tile providers) and the coordinates under the cursor.
		String attribution = Ui.fit(font, map.style.attribution, mw - 60);
		int aw = font.width(attribution);
		g.fill(mw - aw - 8, height - 12, mw, height, 0xB0101418);
		g.text(font, attribution, mw - aw - 4, height - 10, Ui.DIM, false);
		if (!Double.isNaN(map.hoverLat()) && mw - aw > 130) {
			String coords = String.format(Locale.ROOT, "%.5f, %.5f", map.hoverLat(), map.hoverLng());
			g.fill(4, height - 12, 10 + font.width(coords), height, 0xB0101418);
			g.text(font, coords, 7, height - 10, Ui.DIM, false);
		}

		if (map.selection() == null && !map.dragging()) {
			String hint = Ui.fit(font, Component.translatable("realworld.map.hint").getString(), mw - 24);
			int hw = font.width(hint);
			int hx = (mw - hw) / 2, hy = 38;
			g.fill(hx - 8, hy - 5, hx + hw + 8, hy + 12, 0xD0101418);
			g.outline(hx - 8, hy - 5, hw + 16, 17, Ui.BORDER_LIGHT);
			g.text(font, hint, hx, hy, Ui.TEXT, false);
		} else if (map.tool == MapView.Tool.SPAWN) {
			String hint = Ui.fit(font, Component.translatable("realworld.map.hint.spawn").getString(), mw - 24);
			int hw = font.width(hint);
			int hx = (mw - hw) / 2, hy = 38;
			g.fill(hx - 8, hy - 5, hx + hw + 8, hy + 12, 0xD0101418);
			g.text(font, hint, hx, hy, Ui.ACCENT, false);
		}
	}

	/** A box 100 GUI units wide at the map centre, used to size the scale bar. */
	private GeoBox probeBox() {
		double lat = map.centerLat();
		double unitsPerGui = 1.0 / (256.0 * Math.pow(2, map.zoom()) / Math.max(1, minecraft.getWindow().getGuiScale()));
		double dLng = WebMercator.lng(0.5 + 100 * unitsPerGui) - WebMercator.lng(0.5);
		return new GeoBox(lat, 0, lat + 1e-9, dLng);
	}

	private void drawSidebar(GuiGraphicsExtractor g) {
		int x0 = width - sidebar;
		g.fill(x0, 0, width, height, 0xF0111418);
		g.verticalLine(x0, 0, height, Ui.BORDER_LIGHT);
		int sx = x0 + 8, sw = sidebar - 16;

		Component heading = extending == null ? Component.translatable("realworld.map.heading")
			: Component.translatable("realworld.map.heading.extend");
		g.text(font, Ui.fit(font, heading.getString(), sw), sx, 6, Ui.ACCENT, false);
		GeoBox box = map.selection();
		String where = box == null ? Component.translatable("realworld.map.no_selection").getString()
			: place.isBlank() ? String.format(Locale.ROOT, "%.4f, %.4f", box.centerLat(), box.centerLng()) : place;
		g.text(font, Ui.fit(font, where, sw), sx, 17, box == null ? Ui.FAINT : Ui.DIM, false);

		int y = 29;
		g.fill(sx, y, sx + sw, y + 37, 0x40000000);
		g.outline(sx, y, sw, 37, Ui.BORDER);
		if (box != null) {
			String size = Ui.distance(box.widthMeters()) + " × " + Ui.distance(box.heightMeters());
			String blocks = Component.translatable("realworld.map.blocks",
				settings.blocks(box.widthMeters()), settings.blocks(box.heightMeters())).getString();
			g.text(font, Ui.fit(font, size + "  ·  " + Ui.area(box.areaKm2()), sw - 8), sx + 4, y + 4, Ui.TEXT, false);
			g.text(font, Ui.fit(font, blocks, sw - 8), sx + 4, y + 15, Ui.DIM, false);
			Verdict v = verdict();
			int color = switch (v) {
				case QUICK, MEDIUM -> Ui.ACCENT;
				case LONG, VERY_LONG, LARGE, HUGE -> Ui.WARN;
				default -> Ui.BAD;
			};
			Component verdict;
			if (v == Verdict.TOO_SMALL || v == Verdict.TOO_BIG || v == Verdict.NONE) {
				verdict = Component.translatable("realworld.map.verdict." + v.name().toLowerCase(Locale.ROOT));
			} else {
				// The time it will take on this computer, and in how many pieces.
				String time = net.ronnylearn.realworld.arnis.SpeedModel.format(estimate());
				verdict = pieces() > 1 ? Component.literal(time + "  ·  ").append(Component.translatable("realworld.map.pieces", pieces()))
					: Component.literal(time);
			}
			g.text(font, Ui.fit(font, verdict.getString(), sw - 8),
				sx + 4, y + 26, color, false);
		} else {
			g.textWithWordWrap(font, Component.translatable("realworld.map.how").withColor(Ui.FAINT), sx + 4, y + 4, sw - 8, Ui.FAINT, false);
		}

		if (map.hasSpawn()) {
			g.text(font, Ui.fit(font, Component.translatable("realworld.map.spawn_set").getString(), sw), sx, height - 60, Ui.ACCENT, false);
		}
	}

	private void drawResults(GuiGraphicsExtractor g, int mouseX, int mouseY) {
		int x = search.getX(), y = search.getY() + 22, w = search.getWidth();
		if (searchStatus != null) {
			g.fill(x, y, x + w, y + 16, 0xF0111418);
			g.outline(x, y, w, 16, Ui.BORDER_LIGHT);
			g.text(font, searchStatus, x + 6, y + 4, Ui.DIM, false);
			return;
		}
		for (int i = 0; i < results.size(); i++) {
			Geocoder.Place p = results.get(i);
			int ry = y + i * 24;
			boolean hover = mouseX >= x && mouseX < x + w && mouseY >= ry && mouseY < ry + 24;
			g.fill(x, ry, x + w, ry + 24, hover ? 0xF0232A31 : 0xF0111418);
			g.text(font, Ui.fit(font, p.name(), w - 12), x + 6, ry + 3, Ui.TEXT, false);
			g.text(font, Ui.fit(font, p.detail(), w - 12), x + 6, ry + 13, Ui.FAINT, false);
		}
		if (!results.isEmpty()) {
			g.outline(x, y, w, results.size() * 24, Ui.BORDER_LIGHT);
		}
	}

	// ---------------------------------------------------------------- input

	private void runSearch() {
		String query = search.getValue().trim();
		if (query.isEmpty()) {
			return;
		}
		results.clear();
		searchStatus = Component.translatable("realworld.map.searching");
		Geocoder.search(query, minecraft.options.languageCode.replace('_', '-')).whenComplete((found, error) -> minecraft.execute(() -> {
			if (error != null) {
				searchStatus = Component.translatable("realworld.map.search_failed");
			} else if (found.isEmpty()) {
				searchStatus = Component.translatable("realworld.map.not_found");
			} else {
				searchStatus = null;
				results.addAll(found);
			}
		}));
	}

	private void choose(Geocoder.Place p) {
		results.clear();
		searchStatus = null;
		search.setFocused(false);
		GeoBox box = p.bounds();
		GeoBox selection = box != null && box.areaKm2() < 3 && box.widthMeters() > MIN_SIDE * 2 && box.heightMeters() > MIN_SIDE * 2
			? box : GeoBox.around(p.lat(), p.lng(), 800);
		map.setSelection(selection);
		map.flyToFit(selection);
		place = p.detail().isBlank() ? p.name() : p.name() + ", " + p.detail().split(",")[0].trim();
		placeRequested = true;
		if (!nameEdited || name.getValue().isBlank()) {
			name.setValue(p.name());
			nameEdited = false;
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!results.isEmpty()) {
			int x = search.getX(), y = search.getY() + 22, w = search.getWidth();
			if (event.x() >= x && event.x() < x + w && event.y() >= y && event.y() < y + results.size() * 24) {
				choose(results.get((int) ((event.y() - y) / 24)));
				return true;
			}
		}
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		results.clear();
		searchStatus = null;
		if (map.contains(event.x(), event.y())) {
			setFocused(null);
			boolean shift = event.hasShiftDown();
			map.press(event.x(), event.y(), event.button(), shift, doubleClick);
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dx, double dy) {
		if (map.dragging()) {
			map.dragTo(event.x(), event.y());
			return true;
		}
		return super.mouseDragged(event, dx, dy);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (map.dragging()) {
			map.release(event.x(), event.y());
			GeoBox box = map.selection();
			if (box != null && (box.widthMeters() < 1 || box.heightMeters() < 1)) {
				map.setSelection(null);
			}
			updateCreate();
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (map.contains(mouseX, mouseY)) {
			map.scroll(mouseX, mouseY, scrollY);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		if (search.isFocused()) {
			if (event.isConfirmation()) {
				runSearch();
				return true;
			}
			if (event.isEscape() && (!results.isEmpty() || searchStatus != null)) {
				results.clear();
				searchStatus = null;
				return true;
			}
			return super.keyPressed(event);
		}
		// Esc first takes the selected area away; only without one does it leave the map.
		if (event.isEscape() && map.selection() != null) {
			map.setSelection(null);
			updateCreate();
			return true;
		}
		if (name.isFocused()) {
			return super.keyPressed(event);
		}
		if (event.isLeft()) {
			map.pan(-60, 0);
		} else if (event.isRight()) {
			map.pan(60, 0);
		} else if (event.isUp()) {
			map.pan(0, -60);
		} else if (event.isDown()) {
			map.pan(0, 60);
		} else if (key == InputConstants.KEY_EQUALS || key == InputConstants.KEY_ADD) {
			map.zoomBy(1);
		} else if (key == InputConstants.KEY_MINUS) {
			map.zoomBy(-1);
		} else if (key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) {
			map.setSelection(null);
		} else if (key == InputConstants.KEY_1) {
			map.tool = MapView.Tool.PAN;
		} else if (key == InputConstants.KEY_2) {
			map.tool = MapView.Tool.SELECT;
		} else if (key == InputConstants.KEY_3) {
			map.tool = MapView.Tool.SPAWN;
		} else if (event.isConfirmation()) {
			if (create.active) {
				startGeneration();
			}
		} else {
			return super.keyPressed(event);
		}
		updateCreate();
		return true;
	}

	// ---------------------------------------------------------------- actions

	private static String gb(double gb) {
		return String.format(Locale.ROOT, gb < 10 ? "%.1f" : "%.0f", gb);
	}

	public void startGeneration() {
		GeoBox box = map.selection();
		if (box == null || !create.active) {
			return;
		}
		Verdict v = verdict();
		if (v == Verdict.LARGE || v == Verdict.HUGE) {
			// A big area is the player's call, but not by accident.
			double km2 = effectiveKm2();
			double piece = net.ronnylearn.realworld.arnis.Pieces.pieceKm2(settings.load);
			minecraft.gui.setScreen(new net.minecraft.client.gui.screens.ConfirmScreen(yes -> {
				minecraft.gui.setScreen(this);
				if (yes) {
					generate(box);
				}
			}, Component.translatable("realworld.map.big.title"),
				Component.translatable(v == Verdict.HUGE ? "realworld.map.big.huge" : "realworld.map.big.large",
					Ui.area(km2), net.ronnylearn.realworld.arnis.SpeedModel.format(estimate()), pieces(),
					gb(net.ronnylearn.realworld.arnis.Pieces.memoryGb(Math.min(piece, km2)))),
				Component.translatable("realworld.map.big.go"), net.minecraft.network.chat.CommonComponents.GUI_CANCEL));
			return;
		}
		generate(box);
	}

	private void generate(GeoBox box) {
		settings.place = place;
		if (extending == null && settings.worldName.isBlank()) {
			settings.worldName = place.isBlank() ? Component.translatable("realworld.default_name").getString()
				: place.split(",")[0].trim();
		}
		// Without a chosen point the player starts in the middle of the area, not in Arnis's corner.
		// An added area keeps the world's spawn.
		settings.spawnLat = extending != null ? null : map.hasSpawn() ? map.spawnLat() : box.centerLat();
		settings.spawnLng = extending != null ? null : map.hasSpawn() ? map.spawnLng() : box.centerLng();
		rememberView();
		if (extending != null) {
			// The world must be closed and unlocked while Arnis writes into it.
			settings.place = extending.meta.place;
		}
		String[] error = new String[1];
		GenerationTask task = GenerationTask.start(box, settings, error);
		if (task == null) {
			searchStatus = Component.translatable("busy".equals(error[0]) ? "realworld.map.busy" : "realworld.error.library", error[0]);
			return;
		}
		TileCache.get().releaseAll();
		minecraft.gui.setScreen(new GenerationScreen(library, task));
	}

	private void rememberView() {
		config.lat = map.centerLat();
		config.lng = map.centerLng();
		config.zoom = map.zoom();
		config.style = map.style.id;
		if (extending == null) {
			config.remember(settings);
		}
		config.load = settings.load.name();
		config.save();
	}

	@Override
	public void onClose() {
		rememberView();
		TileCache.get().releaseAll();
		minecraft.gui.setScreen(library);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	/** For the self-test: the settings being edited. */
	public GenerationSettings settings() {
		return settings;
	}

	/** For the self-test: the map widget. */
	public MapView mapView() {
		return map;
	}
}
