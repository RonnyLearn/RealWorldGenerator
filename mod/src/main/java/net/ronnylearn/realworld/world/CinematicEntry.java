package net.ronnylearn.realworld.world;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.ronnylearn.realworld.ui.Ui;

/**
 * How a Real World opens, from the "Enter" click to the player's own eyes:
 * <ol>
 * <li>LOADING: the game's loading screens are covered by a dark title card;</li>
 * <li>HOLD: the world exists but stays dark. The chunks are prepared, then the camera turns
 *     a full circle at the start of the flight and at the player's eyes, waiting at every
 *     step until the game has built what it sees, so nothing pops in during the flight;</li>
 * <li>FLIGHT: the dark lifts and the camera spirals down into the player's view.</li>
 * </ol>
 */
public final class CinematicEntry {
	private enum Phase { IDLE, LOADING, HOLD, FLIGHT }

	private static final double DURATION = 7.0;
	private static final double FADE = 1.4;
	private static final double HOLD_MIN = 0.6;
	private static final double HOLD_MAX = 4.5;
	private static final double HEIGHT = 55;
	private static final double DISTANCE = 45;
	/**
	 * Directions looked in while warming up: at the flight start steeply down and towards the
	 * horizon, then level at the player's eyes; eight turns each.
	 */
	private static final int WARM_YAWS = 8;
	private static final int WARM_STEPS = WARM_YAWS * 3;
	/** Longest wait for the server to send every chunk in view distance. */
	private static final double CHUNKS_MAX = 30.0;
	/** Longest wait for one direction, should the game never report it built. */
	private static final double WARM_STEP_MAX = 2.0;

	private static Phase phase = Phase.IDLE;
	/**
	 * The camera of the entry: an entity of its own the game looks through, so the renderer
	 * (Sodium included) places, culls and builds everything from where the camera really is.
	 */
	private static net.minecraft.world.entity.Marker rig;
	private static boolean smartCullWas, madeInvisible;
	private static String worldFolder;
	private static Component title = Component.empty();
	private static Component subtitle = Component.empty();
	private static boolean flyIn;
	private static boolean hudWasHidden;
	private static long phaseStart;
	private static float startYaw;
	/** Warm-up direction being built, -1 before it starts; WARM_STEPS when done. */
	private static int warmStep = -1;
	private static long warmStepAt;
	private static long chunksWaitAt;
	private static long warmStartedAt;
	/** Sections the last warm-up round left unbuilt (shown on the dark card). */
	private static int sectionsLeft;
	/** Longest the whole warm-up may take before the flight starts anyway. */
	private static final double WARM_TOTAL_MAX = 40.0;
	/** Chunks in view distance the client has, of how many; shown on the dark card. */
	private static int chunksHere, chunksWanted;

	private static boolean warm = true;

	private CinematicEntry() {
	}

	/** Called when "Enter" is pressed: from now on the screen goes dark. */
	static void prepare(EntryPlan plan) {
		worldFolder = plan.worldFolder;
		title = Component.literal(plan.title);
		// The place, unless it only repeats the name.
		String place = plan.subtitle == null ? "" : plan.subtitle.trim();
		boolean repeats = place.equalsIgnoreCase(plan.title.trim()) || place.isEmpty();
		subtitle = Component.literal(repeats ? "" : place);
		flyIn = plan.cinematic;
		// With preloading off the player goes in at once, without waiting for chunks to arrive.
		warm = plan.cinematic || plan.preload != ChunkPreloader.Range.OFF;
		enter(Phase.LOADING);
	}

	private static void enter(Phase next) {
		if (next == Phase.LOADING) {
			shownProgress = 0f;
			lastDrawAt = 0;
			warmStep = -1;
			sectionsLeft = 0;
			chunksWaitAt = 0;
			chunksHere = chunksWanted = 0;
		}
		phase = next;
		phaseStart = System.nanoTime();
	}

	private static double phaseSeconds() {
		return (System.nanoTime() - phaseStart) / 1e9;
	}

	/** While a world of the mod is loading, its loading screens are covered by the title card. */
	public static boolean coversLoadingScreens() {
		return phase == Phase.LOADING;
	}

	/** Client tick: moves through the phases. */
	public static void tick(Minecraft mc) {
		if (phase == Phase.IDLE) {
			return;
		}
		boolean inWorld = mc.level != null && mc.player != null;
		switch (phase) {
			case LOADING -> {
				if (inWorld) {
					hudWasHidden = mc.gui.hud.isHidden();
					if (!hudWasHidden) {
						mc.gui.hud.toggle();
					}
					startYaw = mc.player.getYRot() + 140f;
					enter(Phase.HOLD);
					startRig(mc);
				} else if (phaseSeconds() > 1800 || backAtMenus(mc)) {
					// The world never opened.
					phase = Phase.IDLE;
					EntryPlan.cancel();
				}
			}
			case HOLD -> {
				if (!inWorld) {
					finish(mc);
					return;
				}
				if (ChunkPreloader.running()) {
					// Esc while the world is being prepared skips the rest of it.
					if (mc.gui.screen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
						ChunkPreloader.stop();
						mc.gui.setScreen(null);
					}
					// The hold starts counting once the chunks are ready.
					phaseStart = System.nanoTime();
					return;
				}
				if (warm && warmUp(mc)) {
					phaseStart = System.nanoTime();
					return;
				}
				if (!flyIn) {
					// No flight: the view is the player's own from here on.
					stopRig(mc);
				}
				double held = phaseSeconds();
				boolean built = mc.levelRenderer.hasRenderedAllSections();
				if (mc.gui.screen() == null && held >= HOLD_MIN && (built || held >= HOLD_MAX)) {
					enter(Phase.FLIGHT);
				}
			}
			case FLIGHT -> {
				if (!inWorld) {
					finish(mc);
					return;
				}
				if (phaseSeconds() >= (flyIn ? DURATION : FADE)) {
					// The camera is at the player's eyes now: the view becomes theirs without a jump,
					// and the interface is simply there again.
					finish(mc);
				}
			}
			default -> {
			}
		}
	}

	/**
	 * Turns the hidden camera one direction further once the game has built everything in
	 * view (or after a while). True while the warm-up is still going.
	 */
	private static boolean warmUp(Minecraft mc) {
		long now = System.nanoTime();
		if (warmStep < 0) {
			// First every chunk in view distance must have arrived: the server sends them in
			// batches, and one still missing would be a hole during the flight.
			if (chunksWaitAt == 0) {
				chunksWaitAt = now;
			}
			if (!allChunksHere(mc) && (now - chunksWaitAt) / 1e9 < CHUNKS_MAX) {
				return true;
			}
			net.ronnylearn.realworld.RealWorldMod.LOGGER.info("{} of {} chunks in view distance arrived in {} s",
				chunksHere, chunksWanted, (now - chunksWaitAt) / 1_000_000_000L);
			chunksWaitAt = 0;
			warmStep = 0;
			warmStepAt = now;
			warmStartedAt = now;
			return true;
		}
		if (warmStep >= warmSteps()) {
			return false;
		}
		double t = (now - warmStepAt) / 1e9;
		// A few frames with the new direction first, then until nothing is left to build.
		if ((t >= 0.2 && Sections.viewBuilt(mc)) || t >= WARM_STEP_MAX) {
			if (t >= WARM_STEP_MAX) {
				net.ronnylearn.realworld.RealWorldMod.LOGGER.info("Warm-up direction {} still building after {} s", warmStep, WARM_STEP_MAX);
			}
			warmStep++;
			warmStepAt = now;
			if (warmStep == warmSteps()) {
				// Where the renderer can say so section by section (Sodium), go round again
				// until every section with blocks in view distance is built.
				int left = Sections.notBuilt(mc, Math.max(2, mc.options.getEffectiveRenderDistance() - 1));
				sectionsLeft = Math.max(0, left);
				double spent = (now - warmStartedAt) / 1e9;
				if (left > 0 && spent < WARM_TOTAL_MAX) {
					net.ronnylearn.realworld.RealWorldMod.LOGGER.info("{} sections still unbuilt after {} s, another round", left, (int) spent);
					warmStep = 0;
				} else {
					net.ronnylearn.realworld.RealWorldMod.LOGGER.info("Warm-up done in {} s ({} sections left unbuilt{})",
						(int) spent, Math.max(0, left), left < 0 ? ", renderer cannot tell" : "");
				}
			}
		}
		return warmStep < warmSteps();
	}

	/** For the self-test: sections with blocks in view distance not built yet (-1: cannot tell). */
	public static int sectionsNotBuilt() {
		Minecraft mc = Minecraft.getInstance();
		return Sections.notBuilt(mc, Math.max(2, mc.options.getEffectiveRenderDistance() - 1));
	}

	/** Without a flight only the player's own view needs building. */
	private static int warmSteps() {
		return flyIn ? WARM_STEPS : WARM_YAWS;
	}

	/** Whether the client has every chunk within view distance of the player (a disc, as the server sends them). */
	private static boolean allChunksHere(Minecraft mc) {
		int r = Math.max(2, mc.options.getEffectiveRenderDistance() - 1);
		net.minecraft.world.level.ChunkPos c = mc.player.chunkPosition();
		int here = 0, wanted = 0;
		for (int dz = -r; dz <= r; dz++) {
			for (int dx = -r; dx <= r; dx++) {
				if (dx * dx + dz * dz > r * r) {
					continue;
				}
				wanted++;
				if (mc.level.getChunkSource().hasChunk(c.x() + dx, c.z() + dz)) {
					here++;
				}
			}
		}
		chunksHere = here;
		chunksWanted = wanted;
		return here >= wanted;
	}

	/** For the self-test: the flight (or fade) has begun. */
	public static boolean flightStarted() {
		return phase == Phase.FLIGHT || phase == Phase.IDLE;
	}

	private static boolean warming() {
		return phase == Phase.HOLD && warmStep >= 0 && warmStep < warmSteps();
	}

	/** At the title screen or the library a few seconds after "Enter": opening failed or was cancelled. */
	private static boolean backAtMenus(Minecraft mc) {
		return phaseSeconds() > 3 && (mc.gui.screen() instanceof net.minecraft.client.gui.screens.TitleScreen
			|| mc.gui.screen() instanceof net.ronnylearn.realworld.screen.LibraryScreen);
	}

	private static void startRig(Minecraft mc) {
		rig = new net.minecraft.world.entity.Marker(net.minecraft.world.entity.EntityTypes.MARKER, mc.level);
		Vec3 eye = mc.player.getEyePosition();
		rig.setPos(eye.x, eye.y, eye.z);
		rig.setOldPosAndRot();
		mc.setCameraEntity(rig);
		eyeSnap = true;
		// Occlusion culling guesses what is hidden behind what from inside the city; from the
		// air those guesses leave holes, so it is off while the camera flies.
		smartCullWas = mc.smartCull;
		mc.smartCull = false;
		// The player stands where the flight ends; the camera must not meet their own head.
		madeInvisible = !mc.player.isInvisible();
		if (madeInvisible) {
			mc.player.setInvisible(true);
		}
	}

	private static void stopRig(Minecraft mc) {
		if (rig == null) {
			return;
		}
		rig = null;
		eyeSnap = true;
		mc.smartCull = smartCullWas;
		if (mc.player != null) {
			mc.setCameraEntity(mc.player);
			if (madeInvisible) {
				mc.player.setInvisible(false);
			}
		}
		madeInvisible = false;
	}

	/** Every frame, before the game's camera reads it: the rig moves to this frame's point of the path. */
	public static void positionRig(float partialTick) {
		Minecraft mc = Minecraft.getInstance();
		if (rig == null || mc.player == null) {
			return;
		}
		double[] c = camera(mc.player.getEyePosition(partialTick), mc.player.getViewYRot(partialTick), mc.player.getViewXRot(partialTick));
		rig.setPos(c[0], c[1], c[2]);
		rig.setYRot((float) c[3]);
		rig.setXRot((float) c[4]);
		rig.setOldPosAndRot();
	}

	private static void finish(Minecraft mc) {
		stopRig(mc);
		if (phase == Phase.HOLD || phase == Phase.FLIGHT) {
			if (mc.gui.hud.isHidden() != hudWasHidden) {
				mc.gui.hud.toggle();
			}
		}
		phase = Phase.IDLE;
		worldFolder = null;
	}

	/** The camera's eye height must be set at once on its next update (the entity it follows changed). */
	private static volatile boolean eyeSnap;

	public static boolean takeEyeSnap() {
		if (!eyeSnap) {
			return false;
		}
		eyeSnap = false;
		return true;
	}

	/** The camera follows the flight path while the world is dark and during the flight. */
	public static boolean isFlying() {
		return warming() || (flyIn && (phase == Phase.HOLD || phase == Phase.FLIGHT));
	}

	private static double flightProgress() {
		return phase == Phase.FLIGHT ? Math.min(1.0, phaseSeconds() / DURATION) : 0.0;
	}

	/**
	 * The camera for this frame: a point on a descending spiral that ends exactly at the
	 * player's view. Returns {x, y, z, yaw, pitch}.
	 */
	public static double[] camera(Vec3 eye, float yaw, float pitch) {
		if (warming()) {
			// Two rings from where the flight starts (steep, then shallow), one from the player's eyes.
			int ring = flyIn ? warmStep / WARM_YAWS : 2;
			boolean start = ring < 2;
			double[] from = start ? camera0(eye) : new double[] {eye.x, eye.y, eye.z};
			float turn = startYaw + (warmStep % WARM_YAWS) * (360f / WARM_YAWS);
			float look = ring == 0 ? 68 : ring == 1 ? 22 : 10;
			return new double[] {from[0], from[1], from[2], turn, look};
		}
		double e = ease(flightProgress());
		double angle = Math.toRadians(Mth.lerp(e, startYaw, yaw));
		double radius = DISTANCE * (1 - e);
		double x = eye.x + Math.sin(angle) * radius;
		double z = eye.z - Math.cos(angle) * radius;
		double y = eye.y + HEIGHT * (1 - e) * (1 - e * 0.15);
		double camPitch = Mth.lerp(e, 74.0, pitch);
		double camYaw = Mth.lerp(e, startYaw, yaw);
		return new double[] {x, y, z, camYaw, camPitch};
	}

	/** The first point of the flight. */
	private static double[] camera0(Vec3 eye) {
		double angle = Math.toRadians(startYaw);
		return new double[] {eye.x + Math.sin(angle) * DISTANCE, eye.y + HEIGHT, eye.z - Math.cos(angle) * DISTANCE};
	}

	private static double ease(double t) {
		// Slow at both ends, with a long gentle landing.
		return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
	}

	/** How dark the screen is now: 1 while loading and holding, then easing out. */
	private static float darkness() {
		return switch (phase) {
			case LOADING, HOLD -> 1f;
			case FLIGHT -> {
				double t = Math.min(1, phaseSeconds() / FADE);
				yield (float) (1 - t * t * (3 - 2 * t));
			}
			default -> 0f;
		};
	}

	/** The progress bar's shown value; it only moves forward, smoothly. */
	private static float shownProgress;
	private static long lastDrawAt;

	/**
	 * The whole entry as one progress: opening the world (to 25%), preparing chunks (to 50%),
	 * chunks arriving (to 75%), building the view (to 100%).
	 */
	private static float targetProgress() {
		return switch (phase) {
			case LOADING -> (float) (0.25 * (1 - Math.exp(-phaseSeconds() / 8.0)));
			case HOLD -> {
				if (ChunkPreloader.running()) {
					yield 0.25f + 0.25f * ChunkPreloader.progress();
				}
				if (warmStep < 0) {
					yield 0.5f + 0.25f * (chunksWanted > 0 ? chunksHere / (float) chunksWanted : 0f);
				}
				yield warming() ? 0.75f + 0.25f * warmStep / warmSteps() : 1f;
			}
			default -> 1f;
		};
	}

	/** What is happening now, in a few words. */
	private static Component status() {
		if (phase == Phase.LOADING) {
			return Component.translatable("realworld.entry.loading");
		}
		if (ChunkPreloader.running()) {
			return Component.translatable("realworld.entry.preparing", ChunkPreloader.done(), ChunkPreloader.total());
		}
		if (phase == Phase.HOLD && warmStep < 0 && chunksWanted > 0) {
			return Component.translatable("realworld.entry.chunks", chunksHere, chunksWanted);
		}
		return Component.translatable(phase == Phase.HOLD && warming() ? "realworld.entry.building" : "realworld.entry.ready");
	}

	/** Draws the dark title card over whatever is on screen (HUD or a loading screen). */
	public static void drawOverlay(GuiGraphicsExtractor g) {
		float dark = darkness();
		if (dark <= 0.001f) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		Font font = mc.font;
		int w = g.guiWidth(), h = g.guiHeight();
		g.fill(0, 0, w, h, Ui.alpha(0xFF07090C, dark));

		// The card leaves a little faster than the dark, and drifts up as it goes.
		float text = Math.max(0, dark * 1.6f - 0.6f);
		if (text <= 0.01f) {
			return;
		}
		int lift = Math.round((1 - text) * 10);
		int cy = h / 2 - lift;
		g.pose().pushMatrix();
		g.pose().translate(w / 2f, cy - 26);
		g.pose().scale(2f);
		g.centeredText(font, title.copy().withColor(Ui.alpha(Ui.TEXT, text)), 0, 0, Ui.alpha(Ui.TEXT, text));
		g.pose().popMatrix();
		if (!subtitle.getString().isBlank()) {
			g.centeredText(font, subtitle.copy().withColor(Ui.alpha(Ui.DIM, text)), w / 2, cy - 4, Ui.alpha(Ui.DIM, text));
		}

		if (phase != Phase.LOADING && phase != Phase.HOLD) {
			return;
		}
		long now = System.nanoTime();
		float dt = lastDrawAt == 0 ? 0f : Math.min(0.1f, (now - lastDrawAt) / 1e9f);
		lastDrawAt = now;
		shownProgress = Math.max(shownProgress, shownProgress + (targetProgress() - shownProgress) * Math.min(1f, dt * 5f));

		// One thin bar for the whole entry, a soft sheen running inside the filled part only.
		int barW = Math.min(220, w - 40), bx = (w - barW) / 2, by = cy + 14;
		int fill = Math.round(barW * Math.min(1f, shownProgress));
		g.fill(bx, by, bx + barW, by + 2, Ui.alpha(0xFF1E242B, text));
		if (fill > 0) {
			g.fill(bx, by, bx + fill, by + 2, Ui.alpha(Ui.ACCENT_DARK, text));
			g.fill(bx, by, bx + fill, by + 1, Ui.alpha(Ui.ACCENT, text));
			int sheenW = Math.min(24, fill);
			int travel = fill + sheenW;
			int sx = bx - sheenW + (int) ((now / 1e9 * 90) % Math.max(1, travel));
			int s0 = Math.max(bx, sx), s1 = Math.min(bx + fill, sx + sheenW);
			if (s1 > s0) {
				g.fill(s0, by, s1, by + 2, Ui.alpha(0x50FFFFFF, text));
			}
		}

		String percent = Math.round(Math.min(1f, shownProgress) * 100) + "%";
		int pw = font.width(percent);
		String line = Ui.fit(font, status().getString(), barW - pw - 8);
		g.text(font, line, bx, by + 7, Ui.alpha(Ui.FAINT, text), false);
		g.text(font, percent, bx + barW - pw, by + 7, Ui.alpha(Ui.DIM, text), false);
		if (ChunkPreloader.running()) {
			Component skip = Component.translatable("realworld.entry.preparing.skip");
			g.centeredText(font, skip.copy().withColor(Ui.alpha(0xFF4A525B, text)), w / 2, by + 22, Ui.alpha(0xFF4A525B, text));
		}
	}
}
