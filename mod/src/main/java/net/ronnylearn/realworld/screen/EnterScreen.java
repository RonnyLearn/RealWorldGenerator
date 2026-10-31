package net.ronnylearn.realworld.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.ronnylearn.realworld.map.TileCache;
import net.ronnylearn.realworld.ui.Ui;
import net.ronnylearn.realworld.world.EntryPlan;
import net.ronnylearn.realworld.world.Pictures;
import net.ronnylearn.realworld.world.WorldLibrary;
import net.ronnylearn.realworld.world.WorldMeta;
import net.ronnylearn.realworld.world.ZombieSettings;

import java.util.Locale;

/** Where and how to enter a world: the place on the area map, game mode, time, weather, fly-in. */
public class EnterScreen extends Screen {
	private final LibraryScreen library;
	private final WorldLibrary.Entry entry;
	private final EntryPlan plan;
	private final boolean firstVisit;
	private Button spawnButton, lastButton, pointButton;
	private int mapX, mapY, mapW, mapH, rightX;
	private int pointX, pointZ;
	private boolean hasPoint;

	public EnterScreen(LibraryScreen library, WorldLibrary.Entry entry) {
		super(Component.translatable("realworld.enter.title"));
		this.library = library;
		this.entry = entry;
		this.plan = new EntryPlan(entry.folder);
		this.firstVisit = entry.meta.lastPlayed == 0;
		this.plan.place = firstVisit ? EntryPlan.Place.SPAWN : EntryPlan.Place.LAST;
		// The fly-in is for meeting a world; later visits just fade in where the player stands.
		this.plan.cinematic = firstVisit;
		try {
			this.plan.preload = net.ronnylearn.realworld.world.ChunkPreloader.Range.valueOf(entry.meta.preload);
		} catch (Exception e) {
			this.plan.preload = net.ronnylearn.realworld.world.ChunkPreloader.Range.NEAR;
		}
	}

	@Override
	protected void init() {
		int colW = 150, gap = 12;
		int totalW = Math.min(width - 24, 470);
		int left = (width - totalW) / 2;
		mapW = totalW - colW - gap;
		mapH = Math.min(height - 120, (int) (mapW * 0.62));
		mapW = Math.min(mapW, (int) (mapH / 0.62));
		mapX = left;
		mapY = 36;
		int rx = left + totalW - colW, y = mapY;
		rightX = rx;

		int third = (mapW - 8) / 3;
		int by = mapY + mapH + 6;
		spawnButton = addRenderableWidget(Button.builder(Component.translatable("realworld.enter.place.spawn"), b -> setPlace(EntryPlan.Place.SPAWN))
			.bounds(mapX, by, third, 20).tooltip(Tooltip.create(Component.translatable("realworld.enter.place.spawn.tip"))).build());
		lastButton = addRenderableWidget(Button.builder(Component.translatable("realworld.enter.place.last"), b -> setPlace(EntryPlan.Place.LAST))
			.bounds(mapX + third + 4, by, third, 20).tooltip(Tooltip.create(Component.translatable("realworld.enter.place.last.tip"))).build());
		pointButton = addRenderableWidget(Button.builder(Component.translatable("realworld.enter.place.point"), b -> setPlace(EntryPlan.Place.POINT))
			.bounds(mapX + 2 * (third + 4), by, third, 20).tooltip(Tooltip.create(Component.translatable("realworld.enter.place.point.tip"))).build());
		lastButton.active = !firstVisit;
		pointButton.active = entry.meta.area != null;

		addRenderableWidget(cycle(rx, y, colW, "mode", EntryPlan.Mode.values(), () -> plan.mode, v -> plan.mode = v));
		y += 24;
		addRenderableWidget(cycle(rx, y, colW, "time", EntryPlan.Time.values(), () -> plan.time, v -> plan.time = v));
		y += 24;
		addRenderableWidget(cycle(rx, y, colW, "weather", EntryPlan.Weather.values(), () -> plan.weather, v -> plan.weather = v));
		y += 24;
		addRenderableWidget(Button.builder(cinematicLabel(), b -> {
			plan.cinematic = !plan.cinematic;
			b.setMessage(cinematicLabel());
		}).bounds(rx, y, colW, 20).tooltip(Tooltip.create(Component.translatable("realworld.enter.cinematic.tip"))).build());
		y += 24;
		addRenderableWidget(Button.builder(zombieLabel(), b -> {
			ZombieSettings z = zombies();
			ZombieSettings.Difficulty[] all = ZombieSettings.Difficulty.values();
			z.difficulty = all[(z.difficulty.ordinal() + 1) % all.length];
			b.setMessage(zombieLabel());
		}).bounds(rx, y, colW, 20).tooltip(Tooltip.create(Component.translatable("realworld.enter.zombies.tip"))).build());
		y += 24;
		addRenderableWidget(cycle(rx, y, colW, "preload", net.ronnylearn.realworld.world.ChunkPreloader.Range.values(),
			() -> plan.preload, v -> plan.preload = v));

		int cy = height - 28;
		addRenderableWidget(Button.builder(Component.translatable("realworld.enter.go").withStyle(ChatFormatting.BOLD), b -> enter())
			.bounds(width / 2 - 154, cy, 150, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
			.bounds(width / 2 + 4, cy, 150, 20).build());
		updatePlaceButtons();
	}

	private interface Get<T> {
		T get();
	}

	private interface Set<T> {
		void set(T value);
	}

	private <T extends Enum<T>> Button cycle(int x, int y, int w, String key, T[] values, Get<T> get, Set<T> set) {
		return Button.builder(cycleLabel(key, get.get()), b -> {
			T next = values[(get.get().ordinal() + 1) % values.length];
			set.set(next);
			b.setMessage(cycleLabel(key, next));
		}).bounds(x, y, w, 20).tooltip(Tooltip.create(Component.translatable("realworld.enter." + key + ".tip"))).build();
	}

	private static Component cycleLabel(String key, Enum<?> value) {
		return Component.translatable("realworld.enter." + key).append(": ")
			.append(Component.translatable("realworld.enter." + key + "." + value.name().toLowerCase(Locale.ROOT)).withStyle(ChatFormatting.YELLOW));
	}

	private ZombieSettings zombies() {
		if (entry.meta.zombies == null) {
			entry.meta.zombies = new ZombieSettings();
		}
		return entry.meta.zombies;
	}

	private Component zombieLabel() {
		ZombieSettings z = zombies();
		return Component.translatable("realworld.enter.zombies").append(": ")
			.append(Component.translatable("realworld.settings.zombies." + z.difficulty.name().toLowerCase(Locale.ROOT))
				.withStyle(z.enabled() ? ChatFormatting.RED : ChatFormatting.DARK_GRAY));
	}

	private Component cinematicLabel() {
		return Component.translatable("realworld.enter.cinematic").append(" ")
			.append(Component.literal(plan.cinematic ? "✔" : "✘").withStyle(plan.cinematic ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
	}

	private void setPlace(EntryPlan.Place place) {
		plan.place = place;
		if (place == EntryPlan.Place.POINT && !hasPoint && entry.meta.area != null) {
			int[] a = entry.meta.area;
			pointX = (a[0] + a[2]) / 2;
			pointZ = (a[1] + a[3]) / 2;
			hasPoint = true;
		}
		updatePlaceButtons();
	}

	private void updatePlaceButtons() {
		spawnButton.setMessage(selected(Component.translatable("realworld.enter.place.spawn"), plan.place == EntryPlan.Place.SPAWN));
		lastButton.setMessage(selected(Component.translatable("realworld.enter.place.last"), plan.place == EntryPlan.Place.LAST));
		pointButton.setMessage(selected(Component.translatable("realworld.enter.place.point"), plan.place == EntryPlan.Place.POINT));
	}

	private static Component selected(Component label, boolean on) {
		return on ? Component.literal("● ").withStyle(ChatFormatting.GREEN).append(label) : label;
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		WorldMeta m = entry.meta;
		String name = m.name.isBlank() ? entry.folder : m.name;
		g.centeredText(font, Component.translatable("realworld.enter.heading", name).withColor(Ui.ACCENT), width / 2, 10, Ui.TEXT);
		g.centeredText(font, Component.literal(Ui.fit(font, (m.place == null || m.place.isBlank() ? "" : m.place + "  ·  ") + Ui.area(m.bounds().areaKm2()), width - 40)).withColor(Ui.DIM),
			width / 2, 21, Ui.DIM);

		g.fill(mapX - 1, mapY - 1, mapX + mapW + 1, mapY + mapH + 1, Ui.BORDER_LIGHT);
		g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0xFF15191D);
		Pictures.Picture picture = Pictures.get(WorldMeta.picture(entry.path));
		int[] r = pictureRect();
		if (picture != null) {
			g.blit(picture.id(), r[0], r[1], r[0] + r[2], r[1] + r[3], 0f, 1f, 0f, 1f);
		}
		g.outline(r[0], r[1], r[2], r[3], Ui.alpha(Ui.SELECTION, 0.6f));

		if (m.spawn != null && m.area != null) {
			marker(g, m.spawn[0], m.spawn[2], Ui.ACCENT, Component.translatable("realworld.enter.marker.spawn").getString(),
				plan.place == EntryPlan.Place.SPAWN);
		}
		if (hasPoint) {
			marker(g, pointX, pointZ, Ui.SELECTION, Component.translatable("realworld.enter.marker.point").getString(),
				plan.place == EntryPlan.Place.POINT);
		}
		boolean overMap = overPicture(mouseX, mouseY);
		if (overMap && m.area != null) {
			g.requestCursor(com.mojang.blaze3d.platform.cursor.CursorTypes.CROSSHAIR);
			int[] b = toBlock(mouseX, mouseY);
			String coords = "X " + b[0] + "  Z " + b[1];
			g.fill(mapX + 2, mapY + mapH - 13, mapX + 8 + font.width(coords), mapY + mapH - 2, 0xC0101418);
			g.text(font, coords, mapX + 5, mapY + mapH - 11, Ui.TEXT, false);
		}
		if (plan.place == EntryPlan.Place.LAST) {
			Component text = Component.translatable("realworld.enter.last_hint");
			g.fill(mapX, mapY, mapX + mapW, mapY + mapH, 0x70000000);
			g.centeredText(font, text, mapX + mapW / 2, mapY + mapH / 2 - 4, Ui.TEXT);
		}

		int rx = rightX, y = mapY + 6 * 24 + 6;
		int colW = 150;
		Ui.panel(g, rx, y, colW, 58);
		g.text(font, Ui.fit(font, Component.translatable("realworld.enter.info.created", Ui.date(m.created)).getString(), colW - 10), rx + 5, y + 5, Ui.DIM, false);
		g.text(font, Ui.fit(font, Component.translatable("realworld.enter.info.size", Ui.size(entry.size)).getString(), colW - 10), rx + 5, y + 17, Ui.DIM, false);
		g.text(font, Ui.fit(font, Component.translatable("realworld.enter.info.scale", formatScale(m.scale)).getString(), colW - 10), rx + 5, y + 29, Ui.DIM, false);
		g.text(font, Ui.fit(font, Component.translatable(firstVisit ? "realworld.enter.info.first" : "realworld.enter.info.visited",
			Ui.date(m.lastPlayed)).getString(), colW - 10), rx + 5, y + 41, firstVisit ? Ui.ACCENT : Ui.DIM, false);
	}

	private static String formatScale(double scale) {
		return scale == Math.rint(scale) ? String.format(Locale.ROOT, "%.0f", scale) : Double.toString(scale);
	}

	/** Where the whole area picture sits in the map box, letterboxed: x, y, width, height. */
	private int[] pictureRect() {
		Pictures.Picture picture = Pictures.get(WorldMeta.picture(entry.path));
		double aspect = picture != null ? (double) picture.width() / picture.height()
			: entry.meta.bounds().widthMeters() / Math.max(1, entry.meta.bounds().heightMeters());
		int w = mapW, h = (int) Math.round(mapW / aspect);
		if (h > mapH) {
			h = mapH;
			w = (int) Math.round(mapH * aspect);
		}
		return new int[] {mapX + (mapW - w) / 2, mapY + (mapH - h) / 2, w, h};
	}

	private boolean overPicture(double x, double y) {
		int[] r = pictureRect();
		return x >= r[0] && x < r[0] + r[2] && y >= r[1] && y < r[1] + r[3];
	}

	/** Screen position of a block inside the area. */
	private int[] toScreen(int bx, int bz) {
		int[] a = entry.meta.area;
		int[] r = pictureRect();
		double fx = (bx - a[0]) / (double) Math.max(1, a[2] - a[0]);
		double fz = (bz - a[1]) / (double) Math.max(1, a[3] - a[1]);
		return new int[] {(int) Math.round(r[0] + fx * r[2]), (int) Math.round(r[1] + fz * r[3])};
	}

	private int[] toBlock(double sx, double sy) {
		int[] a = entry.meta.area;
		int[] r = pictureRect();
		double fx = Math.max(0, Math.min(1, (sx - r[0]) / r[2]));
		double fz = Math.max(0, Math.min(1, (sy - r[1]) / r[3]));
		return new int[] {(int) Math.round(a[0] + fx * (a[2] - a[0])), (int) Math.round(a[1] + fz * (a[3] - a[1]))};
	}

	private void marker(GuiGraphicsExtractor g, int bx, int bz, int color, String label, boolean active) {
		int[] p = toScreen(bx, bz);
		int px = p[0], py = p[1];
		if (px < mapX || px > mapX + mapW || py < mapY || py > mapY + mapH) {
			return;
		}
		int dark = 0xFF101418;
		g.fill(px - 1, py - 14, px + 1, py, dark);
		g.fill(px - 5, py - 20, px + 5, py - 10, dark);
		g.fill(px - 4, py - 19, px + 4, py - 11, active ? color : Ui.alpha(color, 0.45f));
		g.fill(px - 2, py - 1, px + 2, py + 1, dark);
		if (active) {
			int tw = font.width(label);
			g.fill(px + 7, py - 20, px + 13 + tw, py - 9, 0xD0101418);
			g.text(font, label, px + 10, py - 18, Ui.TEXT, false);
		}
	}

	// ---------------------------------------------------------------- input

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (entry.meta.area != null && overPicture(event.x(), event.y())) {
			int[] b = toBlock(event.x(), event.y());
			pointX = b[0];
			pointZ = b[1];
			hasPoint = true;
			plan.place = EntryPlan.Place.POINT;
			updatePlaceButtons();
			if (doubleClick) {
				enter();
			}
			return true;
		}
		return false;
	}

	/** For the self-test: the same as clicking that block on the map. */
	public void choosePoint(int x, int z) {
		pointX = x;
		pointZ = z;
		hasPoint = true;
		plan.place = EntryPlan.Place.POINT;
		updatePlaceButtons();
	}

	public void enter() {
		WorldMeta m = entry.meta;
		plan.firstVisit = firstVisit;
		plan.title = m.name.isBlank() ? entry.folder : m.name;
		plan.subtitle = m.place;
		plan.longitude = m.bounds().centerLng();
		if (plan.place == EntryPlan.Place.POINT && hasPoint) {
			plan.pointX = pointX;
			plan.pointZ = pointZ;
			plan.hasPoint = true;
		} else if (plan.place == EntryPlan.Place.SPAWN && m.spawn != null) {
			plan.pointX = m.spawn[0];
			plan.pointZ = m.spawn[2];
			plan.hasPoint = true;
		}
		plan.arm();
		m.preload = plan.preload.name();
		// Zombie difficulty and the preload choice belong to the world from now on.
		try {
			entry.meta.write(entry.path);
		} catch (Exception ignored) {
		}
		WorldLibrary.touch(entry.folder);
		Pictures.releaseAll();
		TileCache.get().releaseAll();
		minecraft.createWorldOpenFlows().openWorld(entry.folder, () -> minecraft.gui.setScreen(library.rescan(entry.folder)));
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(library);
	}
}
