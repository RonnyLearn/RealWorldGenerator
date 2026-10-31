package net.ronnylearn.realworld.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.ronnylearn.realworld.RealWorldMod;
import net.ronnylearn.realworld.ui.Ui;
import net.ronnylearn.realworld.world.WorldLibrary;

/** Renames a world: both the library name and the name in level.dat that vanilla's list shows. */
public class RenameScreen extends Screen {
	private final LibraryScreen parent;
	private final WorldLibrary.Entry entry;
	private EditBox name;
	private Button save;

	public RenameScreen(LibraryScreen parent, WorldLibrary.Entry entry) {
		super(Component.translatable("realworld.rename.title"));
		this.parent = parent;
		this.entry = entry;
	}

	@Override
	protected void init() {
		name = new EditBox(font, width / 2 - 120, height / 2 - 12, 240, 20, Component.translatable("realworld.rename.title"));
		name.setMaxLength(64);
		name.setValue(entry.meta.name.isBlank() ? entry.folder : entry.meta.name);
		name.setResponder(v -> save.active = !v.isBlank());
		addRenderableWidget(name);
		save = addRenderableWidget(Button.builder(Component.translatable("realworld.rename.save"), b -> save())
			.bounds(width / 2 - 122, height / 2 + 20, 120, 20).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
			.bounds(width / 2 + 2, height / 2 + 20, 120, 20).build());
		setInitialFocus(name);
	}

	private void save() {
		String value = name.getValue().trim();
		if (value.isEmpty()) {
			return;
		}
		entry.meta.name = value;
		try {
			entry.meta.write(entry.path);
			try (var access = minecraft.getLevelSource().createAccess(entry.folder)) {
				access.renameLevel(value);
			}
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("Rename failed: {}", e.toString());
		}
		minecraft.gui.setScreen(parent.rescan(entry.folder));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (event.isConfirmation()) {
			save();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		g.centeredText(font, title.copy().withColor(Ui.ACCENT), width / 2, height / 2 - 32, Ui.TEXT);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
