package net.ronnylearn.realworld.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.ronnylearn.realworld.arnis.GenerationSettings;
import net.ronnylearn.realworld.arnis.GenerationSettings.Profile;
import net.ronnylearn.realworld.ui.Ui;
import net.ronnylearn.realworld.world.ZombieSettings;

import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * World settings, Lost Cities style: ready-made profiles on the left, every option on the
 * right in three tabs (city, terrain, zombies). Any change to an option makes the profile
 * "Custom". When an area is added to an existing world, the options that world was built
 * with (scale, mode, terrain height) cannot change.
 */
public class WorldSettingsScreen extends Screen {
	private enum Tab { CITY, TERRAIN, ZOMBIES }

	private final Screen parent;
	private final GenerationSettings settings;
	private final Runnable onChange;
	private Tab tab = Tab.CITY;
	private int listX, listY, listW, rowH, panelX, panelW;

	public WorldSettingsScreen(Screen parent, GenerationSettings settings, Runnable onChange) {
		super(Component.translatable("realworld.settings.title"));
		this.parent = parent;
		this.settings = settings;
		this.onChange = onChange;
	}

	@Override
	protected void init() {
		int total = Math.min(width - 20, 470);
		listX = (width - total) / 2;
		listW = Math.min(150, total / 3);
		listY = 30;
		rowH = Math.max(16, Math.min(24, (height - listY - 10) / Profile.values().length));
		panelX = listX + listW + 10;
		panelW = total - listW - 10;

		int tabW = (panelW - 8) / 3;
		for (Tab t : Tab.values()) {
			Button b = addRenderableWidget(Button.builder(tabLabel(t), x -> {
				tab = t;
				rebuildWidgets();
			}).bounds(panelX + t.ordinal() * (tabW + 4), listY, tabW, 20).build());
			b.active = t != tab;
		}

		int y = listY + 30, half = (panelW - 4) / 2;
		boolean locked = settings.extend;
		switch (tab) {
			case CITY -> {
				addRenderableWidget(cycle(panelX, y, panelW, "mode", GenerationSettings.Mode.values(), settings.mode, v -> settings.mode = v)).active = !locked;
				y += 24;
				addRenderableWidget(toggle(panelX, y, half, "interiors", () -> settings.interiors, v -> settings.interiors = v));
				addRenderableWidget(cycle(panelX + half + 4, y, half, "signage", GenerationSettings.Signage.values(), settings.signage, v -> settings.signage = v));
				y += 24;
				addRenderableWidget(toggle(panelX, y, half, "winter", () -> settings.winter, v -> settings.winter = v));
				addRenderableWidget(toggle(panelX + half + 4, y, half, "apocalypse", () -> settings.apocalypse, v -> settings.apocalypse = v));
				y += 24;
			}
			case TERRAIN -> {
				addRenderableWidget(Button.builder(valueLabel("scale", formatNumber(settings.scale) + "×"), b -> {
					settings.scale = next(GenerationSettings.SCALES, settings.scale);
					changed();
				}).bounds(panelX, y, half, 20).tooltip(tip("scale", locked)).build()).active = !locked;
				addRenderableWidget(Button.builder(valueLabel("height", formatNumber(settings.heightMultiplier) + "×"), b -> {
					settings.heightMultiplier = next(GenerationSettings.HEIGHTS, settings.heightMultiplier);
					changed();
				}).bounds(panelX + half + 4, y, half, 20).tooltip(tip("height", locked)).build()).active = !locked && settings.mode.terrain();
				y += 24;
				addRenderableWidget(toggle(panelX, y, half, "caves", () -> settings.caves, v -> settings.caves = v));
				addRenderableWidget(toggle(panelX + half + 4, y, half, "edges", () -> settings.flatSurroundings, v -> settings.flatSurroundings = v));
			}
			case ZOMBIES -> {
				ZombieSettings z = settings.zombies;
				addRenderableWidget(cycle(panelX, y, panelW, "zombies", ZombieSettings.Difficulty.values(), z.difficulty, v -> z.difficulty = v));
				y += 24;
				boolean on = z.enabled();
				addRenderableWidget(cycle(panelX, y, half, "density", ZombieSettings.Density.values(), z.density, v -> z.density = v)).active = on;
				addRenderableWidget(toggle(panelX + half + 4, y, half, "no_burn", () -> z.noBurn, v -> z.noBurn = v)).active = on;
				y += 24;
				addRenderableWidget(toggle(panelX, y, half, "hordes", () -> z.nightHordes, v -> z.nightHordes = v)).active = on;
				addRenderableWidget(toggle(panelX + half + 4, y, half, "armoured", () -> z.armoured, v -> z.armoured = v)).active = on;
				y += 24;
				addRenderableWidget(toggle(panelX, y, half, "runners", () -> z.runners, v -> z.runners = v)).active = on;
				addRenderableWidget(toggle(panelX + half + 4, y, half, "break_doors", () -> z.breakDoors, v -> z.breakDoors = v)).active = on;
			}
		}

		addRenderableWidget(Button.builder(Component.translatable("gui.done"), b -> onClose())
			.bounds(panelX + panelW - 100, height - 26, 100, 20).build());
	}

	// ---------------------------------------------------------------- widgets

	private Component tabLabel(Tab t) {
		return Component.translatable("realworld.settings.tab." + t.name().toLowerCase(Locale.ROOT));
	}

	private static MutableComponent valueLabel(String key, String value) {
		return Component.translatable("realworld.settings." + key).append(": ")
			.append(Component.literal(value).withStyle(ChatFormatting.YELLOW));
	}

	private static Tooltip tip(String key, boolean locked) {
		MutableComponent text = Component.translatable("realworld.settings." + key + ".tip");
		if (locked) {
			text.append("\n").append(Component.translatable("realworld.settings.locked").withStyle(ChatFormatting.GRAY));
		}
		return Tooltip.create(text);
	}

	private Button toggle(int x, int y, int w, String key, BooleanSupplier get, Consumer<Boolean> set) {
		return Button.builder(toggleLabel(key, get.getAsBoolean()), b -> {
			set.accept(!get.getAsBoolean());
			changed();
		}).bounds(x, y, w, 20).tooltip(tip(key, false)).build();
	}

	private static Component toggleLabel(String key, boolean on) {
		return Component.translatable("realworld.settings." + key).append(" ")
			.append(Component.literal(on ? "✔" : "✘").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY));
	}

	private <E extends Enum<E>> Button cycle(int x, int y, int w, String key, E[] values, E current, Consumer<E> set) {
		String name = current.name().toLowerCase(Locale.ROOT);
		return Button.builder(Component.translatable("realworld.settings." + key).append(": ")
				.append(Component.translatable("realworld.settings." + key + "." + name).withStyle(ChatFormatting.YELLOW)), b -> {
			set.accept(values[(current.ordinal() + 1) % values.length]);
			changed();
		}).bounds(x, y, w, 20).tooltip(tip(key, settings.extend && key.equals("mode"))).build();
	}

	private static double next(double[] steps, double value) {
		for (double step : steps) {
			if (step > value + 1e-6) {
				return step;
			}
		}
		return steps[0];
	}

	private static String formatNumber(double v) {
		return v == Math.rint(v) ? String.format(Locale.ROOT, "%.0f", v) : String.format(Locale.ROOT, "%s", v);
	}

	private void changed() {
		settings.profile = Profile.CUSTOM;
		onChange.run();
		rebuildWidgets();
	}

	// ---------------------------------------------------------------- drawing

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title.copy().withColor(Ui.ACCENT), width / 2, 12, Ui.TEXT);

		Profile hovered = null;
		for (Profile p : Profile.values()) {
			int y = listY + p.ordinal() * rowH;
			boolean hover = mouseX >= listX && mouseX < listX + listW && mouseY >= y && mouseY < y + rowH - 2;
			boolean selected = p == settings.profile;
			g.fill(listX, y, listX + listW, y + rowH - 2, selected ? 0xE0213326 : hover ? 0xD81C2127 : Ui.PANEL);
			g.outline(listX, y, listW, rowH - 2, selected ? Ui.ACCENT : hover ? Ui.BORDER_LIGHT : Ui.BORDER);
			g.text(font, Ui.fit(font, Component.translatable("realworld.profile." + p.name().toLowerCase(Locale.ROOT)).getString(), listW - 10),
				listX + 6, y + (rowH - 2 - 8) / 2, selected ? Ui.TEXT : Ui.DIM, false);
			if (hover) {
				hovered = p;
			}
		}

		// The description of the hovered (or chosen) profile under the options.
		Profile shown = hovered != null ? hovered : settings.profile;
		int dy = listY + 30 + 4 * 24 + 6;
		int dh = height - 34 - dy;
		if (dh > 24) {
			Ui.panel(g, panelX, dy, panelW, dh);
			g.text(font, Component.translatable("realworld.profile." + shown.name().toLowerCase(Locale.ROOT)).withColor(Ui.ACCENT), panelX + 6, dy + 6, Ui.ACCENT);
			g.textWithWordWrap(font, Component.translatable("realworld.profile." + shown.name().toLowerCase(Locale.ROOT) + ".desc").withColor(Ui.DIM),
				panelX + 6, dy + 18, panelW - 12, Ui.DIM, false);
		}
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (event.x() >= listX && event.x() < listX + listW && event.y() >= listY) {
			int i = (int) ((event.y() - listY) / rowH);
			if (i >= 0 && i < Profile.values().length) {
				Profile p = Profile.values()[i];
				if (p != Profile.CUSTOM) {
					// Keep what an existing world fixes; take everything else from the profile.
					GenerationSettings.Mode mode = settings.mode;
					double scale = settings.scale, height = settings.heightMultiplier;
					p.apply(settings);
					if (settings.extend) {
						settings.mode = mode;
						settings.scale = scale;
						settings.heightMultiplier = height;
					}
				} else {
					settings.profile = Profile.CUSTOM;
				}
				onChange.run();
				rebuildWidgets();
				return true;
			}
		}
		return super.mouseClicked(event, doubleClick);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
