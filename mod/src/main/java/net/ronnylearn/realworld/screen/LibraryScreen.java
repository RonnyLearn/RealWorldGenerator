package net.ronnylearn.realworld.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.ronnylearn.realworld.arnis.GenerationTask;
import net.ronnylearn.realworld.ui.Ui;
import net.ronnylearn.realworld.world.Pictures;
import net.ronnylearn.realworld.world.WorldLibrary;
import net.ronnylearn.realworld.world.WorldMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** The player's Real Worlds: one card per world with its map from above, place and details. */
public class LibraryScreen extends Screen {
	private static final int ROW = 64;
	private static final int GAP = 4;
	private static final int PIC_W = 96;
	private static final int PIC_H = 54;

	private final Screen parent;
	private List<WorldLibrary.Entry> all = new ArrayList<>();
	private final List<WorldLibrary.Entry> shown = new ArrayList<>();
	private boolean scanned;
	private String filter = "";
	private String selectedFolder;
	private EditBox search;
	private Button play, extend, rename, delete, folder;
	private double scroll, scrollTarget;
	private int listX, listY, listW, listH;
	private long openedAt;

	public LibraryScreen(Screen parent) {
		super(Component.translatable("realworld.library.title"));
		this.parent = parent;
	}

	/** Re-reads the saves folder, e.g. after a world was made, renamed or deleted. */
	public LibraryScreen rescan(String select) {
		scanned = false;
		selectedFolder = select;
		return this;
	}

	@Override
	protected void init() {
		if (!scanned) {
			all = WorldLibrary.scan();
			scanned = true;
			if (selectedFolder == null && !all.isEmpty()) {
				selectedFolder = all.getFirst().folder;
			}
			openedAt = System.currentTimeMillis();
		}
		listW = Math.min(width - 32, 540);
		listX = (width - listW) / 2;
		listY = 50;
		listH = height - listY - 62;

		search = new EditBox(font, width / 2 - 110, 24, 220, 18, Component.translatable("realworld.library.search"));
		search.setHint(Component.translatable("realworld.library.search").withColor(Ui.FAINT));
		search.setValue(filter);
		search.setResponder(value -> {
			filter = value;
			applyFilter();
		});
		addRenderableWidget(search);

		int y1 = height - 54, y2 = height - 30, cx = width / 2;
		play = addRenderableWidget(Button.builder(Component.translatable("realworld.library.play"), b -> playSelected())
			.bounds(cx - 154, y1, 100, 20).build());
		extend = addRenderableWidget(Button.builder(Component.translatable("realworld.library.extend"), b -> extendSelected())
			.bounds(cx - 50, y1, 100, 20)
			.tooltip(Tooltip.create(Component.translatable("realworld.library.extend.tip"))).build());
		addRenderableWidget(Button.builder(Component.translatable("realworld.library.new"), b -> minecraft.gui.setScreen(new MapScreen(this)))
			.bounds(cx + 54, y1, 100, 20).build());
		rename = addRenderableWidget(Button.builder(Component.translatable("realworld.library.rename"), b -> renameSelected())
			.bounds(cx - 154, y2, 72, 20).build());
		delete = addRenderableWidget(Button.builder(Component.translatable("realworld.library.delete"), b -> deleteSelected())
			.bounds(cx - 76, y2, 72, 20).build());
		folder = addRenderableWidget(Button.builder(Component.translatable("realworld.library.folder"), b -> openFolder())
			.bounds(cx + 4, y2, 72, 20)
			.tooltip(Tooltip.create(Component.translatable("realworld.library.folder.tip"))).build());
		addRenderableWidget(Button.builder(Component.translatable("gui.back"), b -> onClose())
			.bounds(cx + 82, y2, 72, 20).build());
		applyFilter();
	}

	private void applyFilter() {
		shown.clear();
		String q = filter.trim().toLowerCase(Locale.ROOT);
		for (WorldLibrary.Entry e : all) {
			if (q.isEmpty() || e.meta.name.toLowerCase(Locale.ROOT).contains(q) || e.meta.place.toLowerCase(Locale.ROOT).contains(q)
				|| e.folder.toLowerCase(Locale.ROOT).contains(q)) {
				shown.add(e);
			}
		}
		updateButtons();
	}

	private WorldLibrary.Entry selected() {
		for (WorldLibrary.Entry e : shown) {
			if (e.folder.equals(selectedFolder)) {
				return e;
			}
		}
		return null;
	}

	private void updateButtons() {
		boolean has = selected() != null;
		if (play != null) {
			play.active = has;
			boolean unfinished = has && selected().meta.unfinished();
			extend.active = has && selected().canExtend() && !generating();
			extend.setMessage(Component.translatable(unfinished ? "realworld.library.finish" : "realworld.library.extend"));
			extend.setTooltip(Tooltip.create(unfinished
				? Component.translatable("realworld.library.finish.tip", selected().meta.pending.size(), selected().meta.pendingTotal)
				: Component.translatable("realworld.library.extend.tip")));
			rename.active = has;
			delete.active = has;
			folder.active = has;
		}
	}

	private boolean wasGenerating = generating();

	/** A generation that ends while the library is open changes the worlds: read them again. */
	@Override
	public void tick() {
		boolean now = generating();
		if (now != wasGenerating) {
			wasGenerating = now;
			GenerationTask task = GenerationTask.current();
			String folder = task != null && task.worldFolder() != null ? task.worldFolder() : selectedFolder;
			rescan(folder);
			rebuildWidgets();
		}
	}

	private static boolean generating() {
		GenerationTask task = GenerationTask.current();
		return task != null && task.state() == GenerationTask.State.RUNNING;
	}

	private int bannerHeight() {
		GenerationTask task = GenerationTask.current();
		return task != null && task.state() == GenerationTask.State.RUNNING ? 26 : 0;
	}

	private int contentHeight() {
		return shown.size() * (ROW + GAP);
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - (listH - bannerHeight()));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		scrollTarget = Math.max(0, Math.min(maxScroll(), scrollTarget));
		scroll += (scrollTarget - scroll) * 0.35;

		g.centeredText(font, Component.translatable("realworld.library.heading").withColor(Ui.ACCENT), width / 2, 10, Ui.TEXT);

		int top = listY;
		GenerationTask task = GenerationTask.current();
		if (bannerHeight() > 0) {
			drawBanner(g, task, mouseX, mouseY);
			top += bannerHeight();
		}

		if (shown.isEmpty()) {
			drawEmpty(g, top);
			return;
		}

		g.enableScissor(listX, top, listX + listW, listY + listH);
		long now = System.currentTimeMillis();
		for (int i = 0; i < shown.size(); i++) {
			int y = top + i * (ROW + GAP) - (int) Math.round(scroll);
			if (y + ROW < top || y > listY + listH) {
				continue;
			}
			// Cards slide in one after another when the screen opens.
			float appear = Math.min(1f, Math.max(0f, (now - openedAt - i * 45L) / 220f));
			int slide = Math.round((1 - appear) * 18);
			drawCard(g, shown.get(i), listX + slide, y, listW - 8, mouseX, mouseY, top, appear);
		}
		g.disableScissor();

		if (maxScroll() > 0) {
			int trackH = listH - bannerHeight();
			int barH = Math.max(20, trackH * trackH / contentHeight());
			int barY = top + (int) ((trackH - barH) * (scroll / maxScroll()));
			g.fill(listX + listW - 4, top, listX + listW - 2, top + trackH, 0x40000000);
			g.fill(listX + listW - 4, barY, listX + listW - 2, barY + barH, Ui.BORDER_LIGHT);
		}
	}

	private void drawBanner(GuiGraphicsExtractor g, GenerationTask task, int mouseX, int mouseY) {
		int x = listX, y = listY, w = listW - 8, h = 22;
		boolean hover = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
		g.fill(x, y, x + w, y + h, hover ? 0xE0213326 : 0xD0182619);
		g.outline(x, y, w, h, Ui.ACCENT_DARK);
		double p = task.progress();
		g.fill(x + 1, y + h - 3, x + 1 + (int) ((w - 2) * p / 100), y + h - 1, Ui.ACCENT);
		g.text(font, Component.translatable("realworld.library.generating", task.settings.worldName,
			String.format(Locale.ROOT, "%.0f", p)), x + 8, y + 6, Ui.TEXT);
		g.text(font, Component.translatable("realworld.library.generating.open"),
			x + w - 8 - font.width(Component.translatable("realworld.library.generating.open")), y + 6, Ui.ACCENT);
	}

	private void drawEmpty(GuiGraphicsExtractor g, int top) {
		boolean filtered = !filter.isBlank() && !all.isEmpty();
		int w = Math.min(listW, 320);
		int lines = filtered ? 0 : font.split(Component.translatable("realworld.library.empty.hint"), w - 32).size();
		int h = filtered ? 40 : 46 + lines * 9;
		int x = (width - w) / 2, y = top + Math.max(10, (listH - h) / 2 - 20);
		Ui.panel(g, x, y, w, h);
		g.centeredText(font, Component.translatable(filtered ? "realworld.library.nothing_found" : "realworld.library.empty")
			.withColor(Ui.TEXT), width / 2, y + 16, Ui.TEXT);
		if (!filtered) {
			g.textWithWordWrap(font, Component.translatable("realworld.library.empty.hint").withColor(Ui.DIM), x + 16, y + 34, w - 32, Ui.DIM, false);
		}
	}

	private void drawCard(GuiGraphicsExtractor g, WorldLibrary.Entry e, int x, int y, int w, int mouseX, int mouseY, int top, float appear) {
		boolean hover = mouseY >= top && mouseY < listY + listH && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW;
		boolean sel = e.folder.equals(selectedFolder);
		g.fill(x, y, x + w, y + ROW, Ui.alpha(sel ? 0xE0202A22 : hover ? 0xD81C2127 : Ui.PANEL, appear));
		g.outline(x, y, w, ROW, sel ? Ui.ACCENT : hover ? Ui.BORDER_LIGHT : Ui.BORDER);

		int px = x + 5, py = y + 5;
		Pictures.Picture picture = Pictures.get(e.picture());
		if (picture != null) {
			Ui.contain(g, picture, px, py, PIC_W, PIC_H);
		} else {
			g.fill(px, py, px + PIC_W, py + PIC_H, 0xFF1D2329);
			g.centeredText(font, Component.translatable("realworld.library.no_picture").withColor(Ui.FAINT), px + PIC_W / 2, py + PIC_H / 2 - 4, Ui.FAINT);
		}
		g.outline(px - 1, py - 1, PIC_W + 2, PIC_H + 2, 0x60000000);

		int tx = px + PIC_W + 10, tw = x + w - tx - 8;
		WorldMeta m = e.meta;
		String name = m.name.isBlank() ? e.folder : m.name;
		g.text(font, Ui.fit(font, name, tw), tx, y + 7, Ui.TEXT);
		g.text(font, Ui.fit(font, m.place.isBlank() ? m.bounds().toArnis() : m.place, tw), tx, y + 19, Ui.DIM);

		String info = Ui.date(m.created) + "  ·  " + Ui.area(m.bounds().areaKm2()) + "  ·  " + Ui.size(e.size);
		g.text(font, Ui.fit(font, info, tw), tx, y + 31, Ui.FAINT);

		int bx = tx;
		bx = badge(g, bx, y + 45, Component.translatable("realworld.badge.scale", formatScale(m.scale)).getString(), 0xFF2B3A4A);
		if (m.terrain) {
			bx = badge(g, bx, y + 45, Component.translatable("realworld.badge.terrain").getString(), 0xFF2E4230);
		}
		if (m.interiors) {
			bx = badge(g, bx, y + 45, Component.translatable("realworld.badge.interiors").getString(), 0xFF43392A);
		}
		if (m.winter) {
			bx = badge(g, bx, y + 45, Component.translatable("realworld.badge.winter").getString(), 0xFF2C4250);
		}
		if (m.apocalypse) {
			bx = badge(g, bx, y + 45, Component.translatable("realworld.badge.apocalypse").getString(), 0xFF4A2A2A);
		}
		if (m.unfinished()) {
			badge(g, bx, y + 45, Component.translatable("realworld.badge.unfinished",
				m.pendingTotal - m.pending.size(), m.pendingTotal).getString(), 0xFF5A4418);
		}
	}

	private int badge(GuiGraphicsExtractor g, int x, int y, String text, int color) {
		int w = font.width(text) + 8;
		g.fill(x, y, x + w, y + 11, color);
		g.text(font, text, x + 4, y + 2, 0xFFE6E9EC, false);
		return x + w + 3;
	}

	private static String formatScale(double scale) {
		return scale == Math.rint(scale) ? String.format(Locale.ROOT, "%.0f", scale) : String.format(Locale.ROOT, "%.2f", scale).replaceAll("0+$", "");
	}

	private int cardAt(double mouseX, double mouseY) {
		int top = listY + bannerHeight();
		if (mouseX < listX || mouseX >= listX + listW - 8 || mouseY < top || mouseY >= listY + listH) {
			return -1;
		}
		int i = (int) ((mouseY - top + scroll) / (ROW + GAP));
		double within = (mouseY - top + scroll) - i * (ROW + GAP);
		return i >= 0 && i < shown.size() && within < ROW ? i : -1;
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (bannerHeight() > 0 && event.y() >= listY && event.y() < listY + 22 && event.x() >= listX && event.x() < listX + listW - 8) {
			minecraft.gui.setScreen(new GenerationScreen(this, GenerationTask.current()));
			return true;
		}
		int i = cardAt(event.x(), event.y());
		if (i >= 0) {
			selectedFolder = shown.get(i).folder;
			updateButtons();
			if (doubleClick) {
				playSelected();
			}
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		scrollTarget -= scrollY * (ROW + GAP) * 0.75;
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (!search.isFocused() && !shown.isEmpty()) {
			int index = shown.indexOf(selected());
			if (event.isDown() || event.isUp()) {
				index = Math.max(0, Math.min(shown.size() - 1, index + (event.isDown() ? 1 : -1)));
				selectedFolder = shown.get(index).folder;
				int y = index * (ROW + GAP);
				int visible = listH - bannerHeight();
				if (y < scrollTarget) {
					scrollTarget = y;
				} else if (y + ROW > scrollTarget + visible) {
					scrollTarget = y + ROW - visible;
				}
				updateButtons();
				return true;
			}
			if (event.isConfirmation() && selected() != null) {
				playSelected();
				return true;
			}
			if (event.key() == InputConstants.KEY_DELETE && selected() != null) {
				deleteSelected();
				return true;
			}
		}
		return super.keyPressed(event);
	}

	private void playSelected() {
		WorldLibrary.Entry e = selected();
		if (e != null) {
			minecraft.gui.setScreen(new EnterScreen(this, e));
		}
	}

	private void extendSelected() {
		WorldLibrary.Entry e = selected();
		if (e == null || !e.canExtend()) {
			return;
		}
		if (e.meta.unfinished()) {
			String[] error = new String[1];
			GenerationTask task = GenerationTask.resume(e, error);
			if (task != null) {
				minecraft.gui.setScreen(new GenerationScreen(this, task));
			} else {
				net.ronnylearn.realworld.RealWorldMod.LOGGER.warn("Could not finish {}: {}", e.folder, error[0]);
			}
			return;
		}
		minecraft.gui.setScreen(new MapScreen(this, e));
	}

	private void renameSelected() {
		WorldLibrary.Entry e = selected();
		if (e != null) {
			minecraft.gui.setScreen(new RenameScreen(this, e));
		}
	}

	private void deleteSelected() {
		WorldLibrary.Entry e = selected();
		if (e == null) {
			return;
		}
		String name = e.meta.name.isBlank() ? e.folder : e.meta.name;
		minecraft.gui.setScreen(new ConfirmScreen(yes -> {
			if (yes) {
				try {
					WorldLibrary.delete(e);
				} catch (Exception ex) {
					net.ronnylearn.realworld.RealWorldMod.LOGGER.error("Could not delete {}", e.path, ex);
				}
				rescan(null);
			}
			minecraft.gui.setScreen(this);
		}, Component.translatable("realworld.library.delete.title"),
			Component.translatable("realworld.library.delete.message", name),
			Component.translatable("realworld.library.delete.confirm"), Component.translatable("gui.cancel")));
	}

	private void openFolder() {
		WorldLibrary.Entry e = selected();
		if (e != null) {
			try {
				new ProcessBuilder("explorer.exe", e.path.toAbsolutePath().toString()).start();
			} catch (Exception ex) {
				net.ronnylearn.realworld.RealWorldMod.LOGGER.warn("Could not open {}: {}", e.path, ex.toString());
			}
		}
	}

	@Override
	public void onClose() {
		Pictures.releaseAll();
		minecraft.gui.setScreen(parent);
	}
}
