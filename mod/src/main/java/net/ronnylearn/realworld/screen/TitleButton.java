package net.ronnylearn.realworld.screen;

import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;

import java.util.List;

/** Puts "Real World" first in the main menu, above "Singleplayer", moving the column one row down. */
public final class TitleButton {
	private static final int ROW = 24;

	private TitleButton() {
	}

	public static void add(Screen screen) {
		List<AbstractWidget> buttons = Screens.getWidgets(screen);
		AbstractWidget singleplayer = null;
		for (AbstractWidget widget : buttons) {
			if (widget.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("menu.singleplayer")) {
				singleplayer = widget;
				break;
			}
		}
		if (singleplayer == null) {
			return;
		}
		int top = singleplayer.getY();
		// Everything in the column from Singleplayer down moves one row lower; where the screen is
		// too short for that, the whole column is lifted by what is missing instead of squeezing
		// the button into another row.
		int lowest = 0;
		for (AbstractWidget widget : buttons) {
			if (widget.getY() >= top && widget.getY() < screen.height - 20) {
				lowest = Math.max(lowest, widget.getY() + widget.getHeight());
			}
		}
		int lift = Math.max(0, Math.min(top - 4, lowest + ROW - (screen.height - 14)));
		for (AbstractWidget widget : buttons) {
			if (widget.getY() >= top && widget.getY() < screen.height - 20) {
				widget.setY(widget.getY() + ROW - lift);
			}
		}
		int x = singleplayer.getX(), y = top - lift, w = singleplayer.getWidth();
		Button button = Button.builder(Component.translatable("realworld.menu.button"),
				b -> Minecraft.getInstance().gui.setScreen(new LibraryScreen(screen)))
			.bounds(x, y, w, 20)
			.tooltip(Tooltip.create(Component.translatable("realworld.menu.button.tip")))
			.build();
		buttons.add(button);
	}
}
