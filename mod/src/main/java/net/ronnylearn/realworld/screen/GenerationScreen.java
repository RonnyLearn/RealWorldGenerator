package net.ronnylearn.realworld.screen;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.ronnylearn.realworld.RealWorldMod;
import net.ronnylearn.realworld.arnis.GenerationTask;
import net.ronnylearn.realworld.map.SmoothTexture;
import net.ronnylearn.realworld.ui.Ui;
import net.ronnylearn.realworld.world.IsoRender;
import net.ronnylearn.realworld.world.WorldLibrary;

import java.util.Locale;

/**
 * Shown while Arnis builds the world: the city itself, block for block, appearing over the
 * street map as Arnis writes it (each new picture sweeps in from the far edge), the stage
 * list, a progress bar and the time left.
 */
public class GenerationScreen extends Screen {
	private static final Identifier[] LIVE = {
		Identifier.fromNamespaceAndPath(RealWorldMod.MOD_ID, "generation/live_a"),
		Identifier.fromNamespaceAndPath(RealWorldMod.MOD_ID, "generation/live_b")
	};
	/** Seconds a new picture of the city takes to sweep in from the far edge. */
	private static final float SWEEP = 1.1f;

	private final LibraryScreen library;
	private final GenerationTask task;
	private final long openedAt = System.nanoTime();
	/** The two pictures of the city on the GPU: the one sweeping in and the one under it. */
	private final IsoRender.Raster[] liveShape = new IsoRender.Raster[2];
	private final boolean[] liveRegistered = new boolean[2];
	private int liveSlot = -1, prevSlot = -1;
	private int liveSerial = -1;
	/** When the current picture started to sweep in (seconds). */
	private float liveAt;
	/** When the first picture of the city arrived (seconds), or -1 while there is none. */
	private float firstLiveAt = -1;
	private float shown;
	/** The time left as shown: changed once a second, so the numbers do not flicker. */
	private long etaShown = -1;
	private float etaAt = -10;
	private GenerationTask.Stage lastStage;
	private GenerationTask.State lastState;
	private long doneAt;
	private float lastFrame;
	/** The animation has caught up with a finished generation. */
	private boolean finished;
	private Button cancel, hide, enter, back;

	public GenerationScreen(LibraryScreen library, GenerationTask task) {
		super(Component.translatable("realworld.generation.title"));
		this.library = library;
		this.task = task;
		// Opened again in the middle of a generation: the bar starts where the work is.
		this.shown = (float) task.progress() / 100f;
	}

	@Override
	protected void init() {
		int cx = width / 2, y = height - 28;
		boolean pieces = task.pieceCount() > 1;
		cancel = addRenderableWidget(Button.builder(Component.translatable(pieces ? "realworld.generation.stop" : "gui.cancel"), b -> {
			task.cancel();
			b.active = false;
		}).bounds(cx - 154, y, 150, 20)
			.tooltip(pieces ? net.minecraft.client.gui.components.Tooltip.create(Component.translatable("realworld.generation.stop.tip")) : null).build());
		hide = addRenderableWidget(Button.builder(Component.translatable("realworld.generation.hide"),
			b -> minecraft.gui.setScreen(library.rescan(null))).bounds(cx + 4, y, 150, 20)
			.tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable("realworld.generation.hide.tip"))).build());
		enter = addRenderableWidget(Button.builder(Component.translatable("realworld.generation.enter"), b -> enter())
			.bounds(cx - 154, y, 150, 20).build());
		back = addRenderableWidget(Button.builder(Component.translatable("realworld.generation.library"), b -> toLibrary())
			.bounds(cx + 4, y, 150, 20).build());
		updateButtons();
	}

	private void updateButtons() {
		GenerationTask.State state = task.state();
		boolean running = state == GenerationTask.State.RUNNING || (state == GenerationTask.State.DONE && !finished);
		cancel.visible = state == GenerationTask.State.RUNNING;
		cancel.active = true;
		hide.visible = state == GenerationTask.State.RUNNING;
		enter.visible = finished;
		back.visible = !running;
		back.setX(finished ? width / 2 + 4 : width / 2 - 75);
	}

	private float seconds() {
		return (System.nanoTime() - openedAt) / 1e9f;
	}

	@Override
	public void tick() {
		GenerationTask.State state = task.state();
		if (state != lastState) {
			lastState = state;
			updateButtons();
			if (state == GenerationTask.State.CANCELLED) {
				toLibrary();
			}
		}
		if (state == GenerationTask.State.DONE && !finished && shown >= 0.999f) {
			finished = true;
			doneAt = System.nanoTime();
			updateButtons();
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.PLAYER_LEVELUP, 1.0f, 0.6f));
		}
		GenerationTask.Stage stage = stage();
		if (stage != lastStage && lastStage != null && !finished) {
			minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.2f + stage.ordinal() * 0.08f, 0.35f));
		}
		lastStage = stage;
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		float time = seconds();
		g.fillGradient(0, 0, width, height, 0xFF0B0F13, 0xFF121920);
		// One soft round glow behind the model, from a smooth texture (no visible steps).
		ensureGlow();
		float breathe = 1f + 0.04f * (float) Math.sin(time * 0.6);
		float gw = width * 0.95f * breathe, gh = height * 0.95f * breathe;
		g.pose().pushMatrix();
		g.pose().translate(width * 0.58f - gw / 2, height * 0.5f - gh / 2);
		g.pose().scale(gw / GLOW_SIZE, gh / GLOW_SIZE);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, GLOW, 0, 0, 0f, 0f, GLOW_SIZE, GLOW_SIZE, GLOW_SIZE, GLOW_SIZE);
		g.pose().popMatrix();

		// A faint grid drifting slowly to the side.
		// The offset keeps its fraction: each line is shared between two pixels, so it glides.
		int cell = 24;
		float shift = (time * 4f) % cell;
		int whole = (int) shift;
		float frac = shift - whole;
		int near = Ui.alpha(0x0CFFFFFF, 1f - frac), far = Ui.alpha(0x0CFFFFFF, frac);
		for (int gx = whole - cell; gx < width; gx += cell) {
			g.fill(gx, 0, gx + 1, height, near);
			g.fill(gx + 1, 0, gx + 2, height, far);
		}
		for (int gy = whole - cell; gy < height; gy += cell) {
			g.fill(0, gy, width, gy + 1, near);
			g.fill(0, gy + 1, width, gy + 2, far);
		}

		// Vignette: the top and bottom fall off into darkness.
		int edge = Math.max(width, height) / 4;
		g.fillGradient(0, 0, width, edge, 0x70000000, 0x00000000);
		g.fillGradient(0, height - edge, width, height, 0x00000000, 0x80000000);
	}

	private static final Identifier GLOW = Identifier.fromNamespaceAndPath(RealWorldMod.MOD_ID, "generation/glow");
	private static final int GLOW_SIZE = 128;
	private static boolean glowReady;

	/** A radial glow drawn once into a texture, dithered so it shows no bands when stretched. */
	private void ensureGlow() {
		if (glowReady) {
			return;
		}
		com.mojang.blaze3d.platform.NativeImage image = new com.mojang.blaze3d.platform.NativeImage(GLOW_SIZE, GLOW_SIZE, true);
		java.util.Random noise = new java.util.Random(7);
		float c = (GLOW_SIZE - 1) / 2f;
		for (int y = 0; y < GLOW_SIZE; y++) {
			for (int x = 0; x < GLOW_SIZE; x++) {
				float dx = (x - c) / c, dy = (y - c) / c;
				float r = Math.min(1f, (float) Math.sqrt(dx * dx + dy * dy));
				float f = 1f - r;
				float a = f * f * (3 - 2 * f) * 0.16f;
				int alpha = Math.max(0, Math.min(255, Math.round(a * 255 + noise.nextFloat() - 0.5f)));
				// Equal red and blue: the same colour whichever channel order the image uses.
				image.setPixel(x, y, alpha << 24 | 0x3A << 16 | 0x8C << 8 | 0x3A);
			}
		}
		minecraft.getTextureManager().register(GLOW, new SmoothTexture("Real World glow", image));
		glowReady = true;
	}

	/**
	 * Before Arnis has written anything: the ground of the area, a rhombus like the city that
	 * will stand on it, laying itself out tile by tile with a light running over it, so the
	 * first seconds already show the world being started.
	 */
	private void drawWaiting(GuiGraphicsExtractor g, int left, int top, int right, int bottom, float dx, float dy, float time, float alpha) {
		if (alpha <= 0.01f) {
			return;
		}
		double w = Math.max(1, task.box.widthMeters()), h = Math.max(1, task.box.heightMeters());
		double aspect = w / h;
		int cols = (int) Math.max(4, Math.min(28, Math.round(16 * Math.sqrt(aspect))));
		int rows = (int) Math.max(4, Math.min(28, Math.round(16 / Math.sqrt(aspect))));
		float areaW = right - left, areaH = bottom - top;
		float planeW = cols + rows, planeH = (cols + rows) / 2f;
		float k = Math.min(areaW / planeW, areaH * 0.8f / planeH) * 0.9f;
		float t = k;
		float ox = left + (areaW - planeW * k) / 2f + dx + rows * t;
		float oy = top + areaH * 0.2f + (areaH * 0.8f - planeH * k) / 2f + dy;
		float maxD = (float) Math.hypot(cols / 2f, rows / 2f);
		// The light runs along the diagonal and comes back every 2.4 s.
		float scan = (time % 2.4f) / 2.4f * (cols + rows + 8) - 4;
		for (int sum = 0; sum <= cols + rows - 2; sum++) {
			for (int i = Math.max(0, sum - rows + 1); i <= Math.min(cols - 1, sum); i++) {
				int j = sum - i;
				float d = (float) Math.hypot(i + 0.5f - cols / 2f, j + 0.5f - rows / 2f) / maxD;
				float appear = Ui.clamp01((time - 0.1f - d * 1.4f) / 0.45f);
				if (appear <= 0) {
					continue;
				}
				float e = appear * appear * (3 - 2 * appear);
				float lit = Math.max(0, 1f - Math.abs(sum - scan) / 3f);
				int color = Ui.mix(0xFF1D2A33, 0xFF3F8F38, 0.15f + 0.55f * lit * lit);
				float cx = ox + (i - j) * t, cy = oy + (i + j) * t / 2f + t / 2f + (1 - e) * 10f;
				g.pose().pushMatrix();
				g.pose().translate(cx, cy);
				g.pose().scale(t, t / 2f);
				g.pose().rotate((float) (Math.PI / 4));
				float s = 0.62f;
				g.pose().scale(s);
				g.fill(-1, -1, 1, 1, Ui.alpha(color, alpha * e * 0.95f));
				g.pose().popMatrix();
			}
		}
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		super.extractRenderState(g, mouseX, mouseY, partialTick);
		updateLive();

		// The model is paced: it follows Arnis but never jumps, so even a seven-second
		// generation shows every stage; a finished one is played to the end a bit faster.
		float time = seconds();
		float dt = Math.min(0.1f, Math.max(0, time - lastFrame));
		lastFrame = time;
		float target = (float) task.progress() / 100f;
		float rate = task.state() == GenerationTask.State.DONE ? 0.45f : 0.2f;
		shown = Math.min(target, shown + rate * dt);

		boolean wide = width >= 420;
		int panelW = wide ? 150 : 0;
		int modelLeft = wide ? panelW + 20 : 10, modelRight = width - 10;
		int modelTop = 34, modelBottom = height - 82;

		// The model stands still: no float or parallax, nothing on screen wobbles.
		float bob = 0f, px = 0f, py = 0f;
		modelArea = new int[] {modelLeft, modelTop, modelRight, modelBottom};
		followDrag(mouseX, mouseY);
		zoom += (zoomTarget - zoom) * Math.min(1f, dt * 12f);
		panX += (panTargetX - panX) * Math.min(1f, dt * 12f);
		panY += (panTargetY - panY) * Math.min(1f, dt * 12f);
		boolean zoomed = zoom > 1.002f || Math.abs(panX) > 0.5f || Math.abs(panY) > 0.5f;
		if (zoomed) {
			// Zoomed in: the city stays inside its own area and is scaled about its centre.
			g.enableScissor(modelLeft, modelTop - 6, modelRight, modelBottom);
			float cx = (modelLeft + modelRight) / 2f, cy = (modelTop + modelBottom) / 2f;
			g.pose().pushMatrix();
			g.pose().translate(cx + panX, cy + panY);
			g.pose().scale(zoom);
			g.pose().translate(-cx, -cy);
		}
		drawLive(g, modelLeft, modelTop, modelRight, modelBottom, bob + py, px, time);
		if (zoomed) {
			g.pose().popMatrix();
			g.disableScissor();
		}
		if (finished) {
			String hint = Ui.fit(font, Component.translatable("realworld.generation.zoom_hint").getString(), modelRight - modelLeft);
			g.centeredText(font, Component.literal(hint).withColor(Ui.FAINT), (modelLeft + modelRight) / 2, modelBottom + 1, Ui.FAINT);
		}

		drawHeader(g);
		if (wide) {
			drawStages(g, 14, 40, panelW);
		}
		drawProgress(g, time);
		if (doneAt != 0) {
			float t = (System.nanoTime() - doneAt) / 1e9f;
			if (t < 0.6f) {
				g.fill(0, 0, width, height, Ui.alpha(0xFFFFFFFF, (0.6f - t) * 0.5f));
			}
		}
	}

	/**
	 * Takes the newest picture of the city once the previous one has swept in, so every
	 * change is seen arriving. The picture is a copy; the task keeps its own.
	 */
	private void updateLive() {
		int serial = task.liveSerial();
		if (serial == liveSerial || (liveSlot >= 0 && seconds() - liveAt < SWEEP)) {
			return;
		}
		IsoRender.Raster frame = task.copyLiveFrame();
		if (frame == null) {
			return;
		}
		liveSerial = serial;
		int slot = liveSlot < 0 ? 0 : 1 - liveSlot;
		if (liveRegistered[slot]) {
			minecraft.getTextureManager().release(LIVE[slot]);
		}
		// Linear filtering: the picture is usually drawn smaller than it is.
		minecraft.getTextureManager().register(LIVE[slot], new SmoothTexture("Real World city", frame.image()));
		liveRegistered[slot] = true;
		liveShape[slot] = frame;
		prevSlot = liveSlot;
		liveSlot = slot;
		liveAt = seconds();
	}

	/**
	 * Draws the city with its ground plane fitted to the model area, so pictures of different
	 * sizes (taller buildings, a finer grid) line up. The newest sweeps in from the back.
	 */
	private void drawLive(GuiGraphicsExtractor g, int left, int top, int right, int bottom, float dy, float dx, float time) {
		if (firstLiveAt < 0 && liveSlot >= 0) {
			firstLiveAt = time;
		}
		float waiting = firstLiveAt < 0 ? 1f : 1f - Ui.clamp01((time - firstLiveAt) / 0.8f);
		drawWaiting(g, left, top, right, bottom, dx, dy, time, waiting);
		if (liveSlot < 0) {
			return;
		}
		float sweep = Ui.clamp01((time - liveAt) / SWEEP);
		if (prevSlot >= 0 && sweep < 1f) {
			blitLive(g, prevSlot, left, top, right, bottom, dx, dy, Integer.MAX_VALUE);
		}
		float e = sweep * sweep * (3 - 2 * sweep);
		float[] at = place(liveShape[liveSlot], left, top, right, bottom, dx, dy);
		int cut = sweep >= 1f ? Integer.MAX_VALUE : (int) (at[1] + (at[3] + 6) * e);
		blitLive(g, liveSlot, left, top, right, bottom, dx, dy, cut);
	}

	/** Image x, y, scale and bottom of the plane (relative to y) for a picture in the model area. */
	private static float[] place(IsoRender.Raster r, int left, int top, int right, int bottom, float dx, float dy) {
		float areaW = right - left, areaH = bottom - top;
		// Room above the plane for what stands on it.
		float k = Math.min(areaW / r.planeWidth(), areaH * 0.8f / r.planeHeight());
		float planeX = left + (areaW - r.planeWidth() * k) / 2f + dx;
		float planeY = top + areaH * 0.2f + (areaH * 0.8f - r.planeHeight() * k) / 2f + dy;
		float y = planeY - r.planeTop() * k;
		return new float[] {planeX, y, k, (r.planeTop() + r.planeHeight()) * k};
	}

	private void blitLive(GuiGraphicsExtractor g, int slot, int left, int top, int right, int bottom, float dx, float dy, int cutY) {
		IsoRender.Raster r = liveShape[slot];
		float[] at = place(r, left, top, right, bottom, dx, dy);
		int w = r.image().getWidth(), h = r.image().getHeight();
		boolean clip = cutY != Integer.MAX_VALUE;
		if (clip) {
			g.enableScissor(0, 0, width, Math.max(0, cutY));
		}
		g.pose().pushMatrix();
		g.pose().translate(at[0], at[1]);
		g.pose().scale(at[2]);
		g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, LIVE[slot], 0, 0, 0f, 0f, w, h, w, h);
		g.pose().popMatrix();
		if (clip) {
			g.disableScissor();
		}
	}

	private void drawHeader(GuiGraphicsExtractor g) {
		String name = task.settings.worldName;
		g.centeredText(font, Component.literal(name).withColor(Ui.TEXT), width / 2, 10, Ui.TEXT);
		String sub = task.settings.place.isBlank() ? task.box.toArnis() : task.settings.place;
		g.centeredText(font, Component.literal(Ui.fit(font, sub + "  ·  " + Ui.area(task.box.areaKm2()), width - 40)).withColor(Ui.DIM),
			width / 2, 21, Ui.DIM);
	}

	/** The stage shown: of the whole run, or of the piece being built when there are several. */
	private GenerationTask.Stage stage() {
		return GenerationTask.Stage.at(task.pieceCount() > 1 ? task.pieceProgress() : shown * 100);
	}

	/** "Piece 3 of 12" while a big area is built in pieces, else empty. */
	private String pieceText() {
		return task.pieceCount() > 1 && task.state() == GenerationTask.State.RUNNING
			? Component.translatable("realworld.generation.piece", task.pieceNumber(), task.pieceCount()).getString() + "  ·  " : "";
	}

	private void drawStages(GuiGraphicsExtractor g, int x, int y, int w) {
		GenerationTask.Stage current = stage();
		boolean done = finished;
		Ui.panel(g, x - 6, y - 6, w, GenerationTask.Stage.values().length * 16 + 10);
		for (GenerationTask.Stage stage : GenerationTask.Stage.values()) {
			boolean complete = done || stage.ordinal() < current.ordinal();
			boolean active = !done && stage == current;
			String mark = complete ? "✔" : active ? "▶" : "·";
			int color = complete ? Ui.ACCENT : active ? Ui.TEXT : Ui.FAINT;
			g.text(font, mark, x, y, color, false);
			g.text(font, Ui.fit(font, Component.translatable(stage.key()).getString(), w - 26), x + 12, y, color, false);
			y += 16;
		}
	}

	private void drawProgress(GuiGraphicsExtractor g, float time) {
		GenerationTask.State state = task.state();
		int barW = Math.min(360, width - 40), x = (width - barW) / 2, y = height - 52;
		double real = task.progress();
		if (state == GenerationTask.State.FAILED) {
			g.centeredText(font, Component.translatable("realworld.generation.failed").withColor(Ui.BAD), width / 2, y - 12, Ui.BAD);
			String error = task.error() == null ? "" : task.error();
			g.textWithWordWrap(font, Component.literal(error).withColor(Ui.DIM), x, y + 2, barW, Ui.DIM, false);
			return;
		}

		// Above the bar: what is being done on the left, how far on the right.
		Component title = switch (state) {
			case DONE -> finished ? Component.translatable("realworld.generation.done", Ui.duration(task.elapsedMillis()))
				: Component.translatable(stage().key());
			case CANCELLED -> Component.translatable("realworld.generation.cancelled");
			default -> Component.literal(pieceText()).append(Component.translatable(stage().key()));
		};
		String percent = String.format(Locale.ROOT, "%.0f%%", Math.min(100, Math.floor(shown * 100)));
		g.text(font, Ui.fit(font, title.getString(), barW - font.width(percent) - 8), x, y - 12, Ui.ACCENT, false);
		g.text(font, percent, x + barW - font.width(percent), y - 12, Ui.TEXT, false);

		// The bar: a thin trough and a fill whose end is drawn to a fraction of a pixel, so it
		// glides instead of stepping.
		int h = 5;
		g.fill(x - 1, y - 1, x + barW + 1, y + h + 1, 0xFF0A0D10);
		g.fill(x, y, x + barW, y + h, 0xFF1A2128);
		float fillF = barW * Ui.clamp01(shown);
		int whole = (int) fillF;
		if (whole > 0) {
			g.fillGradient(x, y, x + whole, y + h, Ui.ACCENT, Ui.ACCENT_DARK);
		}
		float part = fillF - whole;
		if (whole < barW && part > 0.01f) {
			g.fillGradient(x + whole, y, x + whole + 1, y + h, Ui.alpha(Ui.ACCENT, part), Ui.alpha(Ui.ACCENT_DARK, part));
		}

		// Below: time spent on the left, time left on the right (updated once a second).
		g.text(font, Ui.duration(task.elapsedMillis()), x, y + h + 5, Ui.FAINT, false);
		if (state == GenerationTask.State.RUNNING && real > 6) {
			if (time - etaAt >= 1f || etaShown < 0) {
				etaShown = (long) (task.elapsedMillis() * (100 - real) / real);
				etaAt = time;
			}
			if (etaShown >= 1000) {
				String eta = Component.translatable("realworld.generation.eta", Ui.duration(etaShown)).getString();
				g.text(font, eta, x + barW - font.width(eta), y + h + 5, Ui.DIM, false);
			}
		}
	}

	public void enter() {
		String folder = task.worldFolder();
		WorldLibrary.Entry entry = folder == null ? null : WorldLibrary.find(folder);
		cleanup();
		if (entry != null) {
			minecraft.gui.setScreen(new EnterScreen(library.rescan(folder), entry));
		} else {
			minecraft.gui.setScreen(library.rescan(null));
		}
	}

	private void toLibrary() {
		String folder = task.worldFolder();
		cleanup();
		minecraft.gui.setScreen(library.rescan(folder));
	}

	private void releaseTextures() {
		for (int i = 0; i < LIVE.length; i++) {
			if (liveRegistered[i]) {
				minecraft.getTextureManager().release(LIVE[i]);
				liveRegistered[i] = false;
			}
		}
		liveSlot = prevSlot = liveSerial = -1;
	}

	private void cleanup() {
		releaseTextures();
		if (task.state() != GenerationTask.State.RUNNING) {
			task.dispose();
		}
	}

	@Override
	public void removed() {
		releaseTextures();
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return task.state() != GenerationTask.State.RUNNING;
	}

	@Override
	public void onClose() {
		toLibrary();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	// ---------------------------------------------------------------- zoom

	private static final float MAX_ZOOM = 8f;
	private float zoom = 1f, zoomTarget = 1f;
	private float panX, panY, panTargetX, panTargetY;
	private int[] modelArea;

	private boolean overModel(double x, double y) {
		int[] a = modelArea;
		return a != null && x >= a[0] && x < a[2] && y >= a[1] && y < a[3];
	}

	/** The finished city zooms towards the cursor: the point under it stays where it is. */
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (!finished || !overModel(mouseX, mouseY) || scrollY == 0) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		float next = Math.max(1f, Math.min(MAX_ZOOM, zoomTarget * (float) Math.pow(1.25, scrollY)));
		float cx = (modelArea[0] + modelArea[2]) / 2f, cy = (modelArea[1] + modelArea[3]) / 2f;
		float wx = (float) (mouseX - cx - panTargetX) / zoomTarget, wy = (float) (mouseY - cy - panTargetY) / zoomTarget;
		zoomTarget = next;
		panTargetX = (float) (mouseX - cx) - wx * next;
		panTargetY = (float) (mouseY - cy) - wy * next;
		clampPan();
		return true;
	}

	/** The city is being dragged with the left button; followed every frame from the cursor. */
	private boolean draggingCity;
	private double dragLastX, dragLastY;

	private void followDrag(int mouseX, int mouseY) {
		if (!draggingCity) {
			return;
		}
		// Let go outside the window: no release ever arrives.
		if (!minecraft.mouseHandler.isLeftPressed()) {
			draggingCity = false;
			return;
		}
		panTargetX += (float) (mouseX - dragLastX);
		panTargetY += (float) (mouseY - dragLastY);
		dragLastX = mouseX;
		dragLastY = mouseY;
		clampPan();
		panX = panTargetX;
		panY = panTargetY;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (draggingCity && event.button() == 0) {
			draggingCity = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		if (!finished || !overModel(event.x(), event.y())) {
			return false;
		}
		// Right click or a double click: the whole city again.
		if (event.button() == 1 || doubleClick) {
			draggingCity = false;
			zoomTarget = 1f;
			panTargetX = panTargetY = 0f;
			return true;
		}
		if (event.button() == 0) {
			draggingCity = true;
			dragLastX = event.x();
			dragLastY = event.y();
			return true;
		}
		return false;
	}

	/** Keeps at least part of the city in view: the pan is limited to how far the zoom reaches. */
	private void clampPan() {
		if (modelArea == null) {
			return;
		}
		float w = (modelArea[2] - modelArea[0]) / 2f * (zoomTarget - 1), h = (modelArea[3] - modelArea[1]) / 2f * (zoomTarget - 1);
		panTargetX = Math.max(-w, Math.min(w, panTargetX));
		panTargetY = Math.max(-h, Math.min(h, panTargetY));
	}

	/** For the self-test: zooms in on the centre as the wheel would. */
	public void zoomForTest(float factor) {
		zoomTarget = zoom = Math.max(1f, Math.min(MAX_ZOOM, factor));
		panTargetX = panTargetY = panX = panY = 0f;
	}

	/** For the self-test. */
	public GenerationTask task() {
		return task;
	}
}
