package net.ronnylearn.realworld.dev;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.ronnylearn.realworld.RealWorldMod;
import net.ronnylearn.realworld.arnis.GenerationTask;
import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.screen.EnterScreen;
import net.ronnylearn.realworld.screen.GenerationScreen;
import net.ronnylearn.realworld.screen.LibraryScreen;
import net.ronnylearn.realworld.screen.MapScreen;
import net.ronnylearn.realworld.world.WorldLibrary;
import net.ronnylearn.realworld.world.WorldMeta;

import java.io.File;
import java.nio.file.Files;
import java.util.List;

/**
 * Automated walk through the whole mod, for development only. It runs when the file
 * rw-selftest.flag exists in the game folder (the window is then hidden): title screen,
 * library, map with a search and a selection, a real generation, the entry screen, the
 * fly-in, back to the library, quit. Results go to the log as [RW-SELFTEST]
 * lines and screenshots to screenshots/rw-*.png.
 */
public final class SelfTest {
	private static final double LAT = 55.7636, LNG = 37.5935;

	private static int step;
	private static int wait;
	/** 0: the large area is not checked yet, 1: its confirmation was asked for, 2: done. */
	private static int largeArea;
	private static MapScreen largeMap;
	private static int ticks;
	private static int shots;
	private static int failures;
	private static String world;

	private SelfTest() {
	}

	public static void init() {
		if (!new File("rw-selftest.flag").exists()) {
			return;
		}
		ClientTickEvents.END_CLIENT_TICK.register(SelfTest::tick);
		log("started");
	}

	private static void log(String message) {
		RealWorldMod.LOGGER.info("[RW-SELFTEST] {}", message);
	}

	private static void check(boolean ok, String what) {
		log((ok ? "PASS " : "FAIL ") + what);
		if (!ok) {
			failures++;
		}
	}

	private static void shot(Minecraft mc, String name) {
		shots++;
		Screenshot.grab(mc.gameDirectory, "rw-" + String.format("%02d", shots) + "-" + name + ".png", mc.gameRenderer.mainRenderTarget(), 1, m -> { });
		log("screenshot " + name);
	}

	private static void next(int delayTicks) {
		step++;
		wait = delayTicks;
	}

	private static void tick(Minecraft mc) {
		ticks++;
		if (ticks > 20 * 60 * 20) {
			log("TIMEOUT at step " + step);
			mc.stop();
			return;
		}
		if (wait > 0) {
			wait--;
			return;
		}
		Screen screen = mc.gui.screen();
		if (tryAreas != null) {
			tryNext(mc);
			return;
		}
		if (step >= 7 && step <= 11 && stopAndFinish(mc, screen)) {
			return;
		}
		switch (step) {
			case 0 -> {
				if (screen instanceof TitleScreen && mc.gui.overlay() == null) {
					renderListedWorlds(mc);
					if (loadTryAreas(mc)) {
						return;
					}
					next(60);
				} else if (ticks % 200 == 0) {
					log("waiting for the title screen, screen: " + (screen == null ? "none" : screen.getClass().getSimpleName()));
				}
			}
			case 1 -> {
				boolean found = screen.children().stream().anyMatch(c -> c instanceof net.minecraft.client.gui.components.AbstractWidget w
					&& w.getMessage().getContents() instanceof TranslatableContents t && t.getKey().equals("realworld.menu.button"));
				check(found, "main menu has the Real World button");
				shot(mc, "title");
				mc.gui.setScreen(new LibraryScreen(screen));
				next(30);
			}
			case 2 -> {
				shot(mc, "library-empty");
				mc.gui.setScreen(new MapScreen((LibraryScreen) screen));
				next(5);
			}
			case 3 -> {
				MapScreen map = (MapScreen) screen;
				map.mapView().setView(LAT, LNG, 15.5);
				net.ronnylearn.realworld.map.Geocoder.search("Патриаршие пруды, Москва", "ru").whenComplete((places, error) ->
					check(error == null && !places.isEmpty(), "place search finds " + (places == null ? 0 : places.size()) + " result(s)"
						+ (places != null && !places.isEmpty() ? ": " + places.getFirst().name() : "")));
				map.mapView().style = net.ronnylearn.realworld.map.TileStyle.SATELLITE;
				next(160);
			}
			case 4 -> {
				shot(mc, "map-satellite");
				MapScreen map = (MapScreen) screen;
				map.mapView().style = net.ronnylearn.realworld.map.TileStyle.STANDARD;
				// Small pieces, so the test area is built in four of them and can be stopped half way.
				net.ronnylearn.realworld.arnis.Pieces.testPieceKm2 = 0.025;
				// A 12 km square first: past the old limit, it must ask, not refuse.
				map.mapView().setSelection(GeoBox.around(LAT, LNG, 12000));
				// The first world is a zombie apocalypse, so the zombies get tested too.
				net.ronnylearn.realworld.arnis.GenerationSettings.Profile.WASTELAND.apply(map.settings());
				next(80);
			}
			case 5 -> {
				if (largeArea == 0) {
					shot(mc, "map-large");
				double[] oneKm = net.ronnylearn.realworld.arnis.SpeedModel.estimate(1, 1, net.ronnylearn.realworld.arnis.GenerationSettings.Load.BALANCED);
				log("speed model: median " + String.format(java.util.Locale.ROOT, "%.2f", net.ronnylearn.realworld.arnis.SpeedModel.medianRate())
					+ " km2/s; 1 km2 at balanced load: " + net.ronnylearn.realworld.arnis.SpeedModel.format(oneKm));
					largeMap = (MapScreen) screen;
					largeMap.startGeneration();
					largeArea = 1;
					wait = 20;
					return;
				}
				if (largeArea == 1) {
					check(screen instanceof net.minecraft.client.gui.screens.ConfirmScreen, "a 144 km² area asks for confirmation instead of being refused");
					shot(mc, "map-large-confirm");
					mc.gui.setScreen(largeMap);
					largeMap.mapView().setSelection(GeoBox.around(LAT, LNG, 280));
					largeArea = 2;
					wait = 80;
					return;
				}
				shot(mc, "map-selection");
				MapScreen map = (MapScreen) screen;
				check(map.mapView().selection() != null, "selection is set");
				map.startGeneration();
				next(10);
			}
			case 6 -> {
				check(screen instanceof GenerationScreen, "generation screen opened");
				if (!(screen instanceof GenerationScreen)) {
					mc.stop();
					return;
				}
				next(0);
			}
			case 7, 8, 9, 10 -> {
				GenerationTask task = ((GenerationScreen) screen).task();
				double[] at = {3, 25, 55, 92};
				if (task.state() != GenerationTask.State.RUNNING || task.progress() >= at[step - 7]) {
					shot(mc, "generating-" + (int) at[step - 7]);
					next(0);
				}
			}
			case 11 -> {
				GenerationTask task = ((GenerationScreen) screen).task();
				if (task.state() == GenerationTask.State.RUNNING) {
					return;
				}
				check(task.state() == GenerationTask.State.DONE, "generation finished: " + task.state()
					+ (task.error() != null ? " (" + task.error() + ")" : "") + " in " + task.elapsedMillis() / 1000 + " s");
				check(task.iso() != null, "exact isometric city rendered");
				check(task.liveSerial() > 2, "the city was drawn while it was built: " + task.liveSerial() + " pictures, "
					+ task.liveUpdates() + " of them from Arnis's live data");
				check(resumed, "the generation was stopped after 2 of " + task.pieceCount() + " pieces and finished from the library");
				// Long enough for the block model to finish and the exact city to fade in.
				next(320);
			}
			case 12 -> {
				if (!zoomShot) {
					// The finished city, then zoomed in four times on its centre.
					zoomShot = true;
					shot(mc, "generation-done");
					((GenerationScreen) screen).zoomForTest(4f);
					wait = 30;
					return;
				}
				shot(mc, "generation-zoom");
				((GenerationScreen) screen).zoomForTest(1f);
				GenerationTask task = ((GenerationScreen) screen).task();
				world = task.worldFolder();
				if (world == null) {
					finish(mc);
					return;
				}
				var dir = WorldLibrary.savesDir().resolve(world);
				check(Files.isRegularFile(dir.resolve("level.dat")), "level.dat written");
				check(Files.isDirectory(dir.resolve("region")), "region folder written");
				check(Files.isRegularFile(WorldMeta.mapImage(dir)), "map.png written");
				WorldMeta meta = WorldMeta.read(dir);
				check(meta != null && meta.area != null, "meta.json with area");
				((GenerationScreen) screen).enter();
				next(40);
			}
			case 13 -> {
				check(screen instanceof EnterScreen, "entry screen opened");
				shot(mc, "enter");
				// A point a quarter of the way into the area, chosen on the map: the player must appear there.
				WorldMeta chosenIn = WorldMeta.read(WorldLibrary.savesDir().resolve(world));
				if (screen instanceof EnterScreen enterScreen && chosenIn != null && chosenIn.area != null) {
					int[] a = chosenIn.area;
					chosenX = a[0] + (a[2] - a[0]) / 4;
					chosenZ = a[1] + (a[3] - a[1]) / 4;
					enterScreen.choosePoint(chosenX, chosenZ);
				}
				((EnterScreen) screen).enter();
				next(20);
			}
			case 14 -> {
				if (mc.level != null && mc.player != null && mc.gui.screen() == null) {
					log("joined world at " + mc.player.blockPosition());
					next(55);
				} else if (ticks % 100 == 0) {
					log("waiting to join, screen: " + (screen == null ? "none" : screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\""));
				}
			}
			case 15 -> {
				if (net.ronnylearn.realworld.world.ChunkPreloader.running() || !net.ronnylearn.realworld.world.CinematicEntry.flightStarted()) {
					// The dark card stays up while the world is made ready; one picture of it, half way.
					if (!preparingShot && ticks % 20 == 0 && (net.ronnylearn.realworld.world.ChunkPreloader.done() > 0
						|| !net.ronnylearn.realworld.world.ChunkPreloader.running())) {
						preparingShot = true;
						shot(mc, "entry-card");
					}
					return;
				}
				int unbuilt = net.ronnylearn.realworld.world.CinematicEntry.sectionsNotBuilt();
				check(unbuilt <= 0, "sections in view distance left unbuilt as the flight starts: "
					+ (unbuilt < 0 ? "renderer cannot tell" : unbuilt));
				shot(mc, "fly-in-high");
				next(50);
			}
			case 16 -> {
				log("sections unbuilt in mid-flight: " + net.ronnylearn.realworld.world.CinematicEntry.sectionsNotBuilt());
				shot(mc, "fly-in-low");
				next(320);
			}
			case 17 -> {
				shot(mc, "landed");
				var server = mc.getSingleplayerServer();
				int zombies = server == null ? -1 : server.overworld().getEntities(
					net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.monster.zombie.Zombie.class),
					z -> z.entityTags().contains(net.ronnylearn.realworld.world.ZombieDirector.TAG)).size();
				check(zombies > 0, "zombies around the player: " + zombies);
				if (server != null) {
					// Noon for a few seconds: no zombie of the mod may be on fire.
					server.execute(() -> server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSuppressedOutput(), "time set noon"));
				}
				check(mc.getCameraEntity() == mc.player && mc.smartCull && !mc.player.isInvisible(),
					"after the flight the view is the player's own again (camera, culling, visible model)");
				var pos = mc.player.blockPosition();
				check(chosenX == Integer.MIN_VALUE || (Math.abs(pos.getX() - chosenX) <= 2 && Math.abs(pos.getZ() - chosenZ) <= 2),
					"appeared at the point chosen on the map: " + pos.getX() + " " + pos.getZ() + " (chosen " + chosenX + " " + chosenZ + ")");
				log("landed at " + pos + ", block below: " + mc.level.getBlockState(pos.below()));
				check(!mc.level.getBlockState(pos.below()).isAir() || mc.player.getAbilities().flying, "standing on the generated world");
				check(Files.isRegularFile(WorldMeta.blocksImage(WorldLibrary.savesDir().resolve(world))), "block map of the area saved");
				WorldMeta prepared = WorldMeta.read(WorldLibrary.savesDir().resolve(world));
				check(prepared != null && !prepared.preloaded.isEmpty() && !net.ronnylearn.realworld.world.ChunkPreloader.running(),
					"chunks prepared before the fly-in: " + net.ronnylearn.realworld.world.ChunkPreloader.done() + " of "
						+ net.ronnylearn.realworld.world.ChunkPreloader.total());
				next(160);
			}
			case 18 -> {
				if (!burnChecked && mc.getSingleplayerServer() != null) {
					burnChecked = true;
					var server = mc.getSingleplayerServer();
					var list = server.overworld().getEntities(
						net.minecraft.world.level.entity.EntityTypeTest.forClass(net.minecraft.world.entity.monster.zombie.Zombie.class),
						z -> z.entityTags().contains(net.ronnylearn.realworld.world.ZombieDirector.TAG));
					long burning = list.stream().filter(net.minecraft.world.entity.Entity::isOnFire).count();
					double nearest = list.stream().mapToDouble(z -> Math.sqrt(z.distanceToSqr(server.overworld().players().getFirst()))).min().orElse(-1);
					check(!list.isEmpty() && burning == 0, "at noon " + burning + " of " + list.size() + " zombies are burning");
					log("nearest zombie " + Math.round(nearest) + " blocks away");
					mc.disconnectFromWorld(Component.literal("self-test"));
					return;
				}
				if (screen instanceof TitleScreen) {
					mc.gui.setScreen(new LibraryScreen(screen));
					next(60);
				}
			}
			case 19 -> {
				shot(mc, "library-after");
				List<WorldLibrary.Entry> entries = WorldLibrary.scan();
				check(!entries.isEmpty(), "library lists " + entries.size() + " world(s)");
				WorldLibrary.Entry entry = WorldLibrary.find(world);
				check(entry != null && entry.canExtend(), "the new world can be extended");
				if (entry == null) {
					finish(mc);
					return;
				}
				widthBefore = picture(entry.path)[0];
				areasBefore = net.ronnylearn.realworld.world.WorldPicture.areas(entry.path).size();
				check(areasBefore == 4 && !entry.meta.unfinished(), "the world is built from " + areasBefore + " pieces, none left");
				mc.gui.setScreen(new MapScreen((LibraryScreen) screen, entry));
				next(100);
			}
			case 20 -> {
				// The next area east of the first one, overlapping it a little.
				MapScreen map = (MapScreen) screen;
				GeoBox first = GeoBox.around(LAT, LNG, 280);
				double width = first.maxLng() - first.minLng();
				map.mapView().setSelection(GeoBox.around(LAT, LNG + width * 0.9, 280));
				next(60);
			}
			case 21 -> {
				shot(mc, "map-extend");
				((MapScreen) screen).startGeneration();
				next(10);
			}
			case 22 -> {
				if (!(screen instanceof GenerationScreen gen)) {
					check(false, "extension started (screen " + (screen == null ? "none" : screen.getClass().getSimpleName()) + ")");
					finish(mc);
					return;
				}
				if (gen.task().state() == GenerationTask.State.RUNNING) {
					return;
				}
				check(gen.task().state() == GenerationTask.State.DONE, "extension finished: " + gen.task().state()
					+ (gen.task().error() != null ? " (" + gen.task().error() + ")" : ""));
				next(80);
			}
			case 23 -> {
				shot(mc, "extend-done");
				var dir = WorldLibrary.savesDir().resolve(world);
				WorldMeta meta = WorldMeta.read(dir);
				check(meta != null && meta.areaCount == 2, "world has 2 areas: " + (meta == null ? "?" : meta.areaCount));
				int areasAfter = net.ronnylearn.realworld.world.WorldPicture.areas(dir).size();
				check(areasAfter > areasBefore, "manifest grew: " + areasBefore + " -> " + areasAfter + " pieces");
				int widthAfter = picture(dir)[0];
				check(widthAfter > widthBefore, "world picture grew: " + widthBefore + " -> " + widthAfter + " px");
				((GenerationScreen) screen).onClose();
				next(60);
			}
			case 24 -> {
				shot(mc, "library-extended");
				next(10);
			}
			default -> finish(mc);
		}
	}

	/**
	 * Renders the exact isometric view of every world listed in rw-iso-worlds.txt (one folder
	 * per line) into the screenshots folder: a check of the view on real, large worlds. Reads
	 * the worlds only.
	 */
	private static void renderListedWorlds(Minecraft mc) {
		java.nio.file.Path list = mc.gameDirectory.toPath().resolve("rw-iso-worlds.txt");
		if (!Files.isRegularFile(list)) {
			return;
		}
		try {
			for (String line : Files.readAllLines(list)) {
				if (line.isBlank()) {
					continue;
				}
				java.nio.file.Path dir = java.nio.file.Path.of(line.trim());
				long started = System.nanoTime();
				try (var image = net.ronnylearn.realworld.world.IsoRender.render(net.ronnylearn.realworld.world.WorldPicture.areas(dir))) {
					java.nio.file.Path out = mc.gameDirectory.toPath().resolve("screenshots").resolve("rw-iso-" + dir.getFileName() + ".png");
					Files.createDirectories(out.getParent());
					image.writeToFile(out);
					log("iso of " + dir.getFileName() + ": " + image.getWidth() + "x" + image.getHeight() + " in "
						+ (System.nanoTime() - started) / 1_000_000 + " ms -> " + out.getFileName());
				}
			}
		} catch (Exception e) {
			check(false, "iso of listed worlds: " + e);
		}
	}

	/** Areas listed in rw-try-areas.txt ("name;minLat,minLng,maxLat,maxLng[;PROFILE]" per line), generated one by one instead of the walk-through. */
	private static java.util.ArrayDeque<String[]> tryAreas;
	private static GenerationTask tryTask;
	private static String tryName;

	private static boolean loadTryAreas(Minecraft mc) {
		java.nio.file.Path list = mc.gameDirectory.toPath().resolve("rw-try-areas.txt");
		if (!Files.isRegularFile(list)) {
			return false;
		}
		tryAreas = new java.util.ArrayDeque<>();
		try {
			for (String line : Files.readAllLines(list)) {
				if (line.contains(";")) {
					tryAreas.add(line.trim().split(";", 3));
				}
			}
		} catch (Exception e) {
			log("cannot read the try list: " + e);
		}
		log("trying " + tryAreas.size() + " area(s)");
		return true;
	}

	private static void tryNext(Minecraft mc) {
		if (tryTask != null) {
			if (tryTask.state() == GenerationTask.State.RUNNING) {
				if (ticks % 200 == 0) {
					log("TRY " + tryName + " at " + Math.round(tryTask.progress()) + "%: " + tryTask.arnisMessage());
				}
				return;
			}
			String folder = tryTask.worldFolder();
			WorldMeta meta = folder == null ? null : WorldMeta.read(WorldLibrary.savesDir().resolve(folder));
			log("TRY " + tryName + ": " + tryTask.state() + " in " + tryTask.elapsedMillis() / 1000 + " s"
				+ (tryTask.error() != null ? " error: " + tryTask.error() : "")
				+ (meta != null && meta.area != null ? " area " + java.util.Arrays.toString(meta.area) + " spawn " + java.util.Arrays.toString(meta.spawn) : ""));
			if (folder != null) {
				shotIso(mc, tryTask, tryName);
			}
			tryTask.dispose();
			tryTask = null;
		}
		String[] next = tryAreas.poll();
		if (next == null) {
			tryAreas = null;
			finish(mc);
			return;
		}
		tryName = next[0];
		String[] c = next[1].split(",");
		GeoBox box = GeoBox.of(Double.parseDouble(c[0]), Double.parseDouble(c[1]), Double.parseDouble(c[2]), Double.parseDouble(c[3]));
		net.ronnylearn.realworld.arnis.GenerationSettings s = new net.ronnylearn.realworld.arnis.GenerationSettings();
		// An optional third field names the profile (CITY when left out).
		net.ronnylearn.realworld.arnis.GenerationSettings.Profile.valueOf(next.length > 2 ? next[2].trim() : "CITY").apply(s);
		s.worldName = "try " + tryName;
		s.load = net.ronnylearn.realworld.arnis.GenerationSettings.Load.MAX;
		String[] error = new String[1];
		tryTask = GenerationTask.start(box, s, error);
		log("TRY " + tryName + " " + box.toArnis() + (tryTask == null ? " did not start: " + error[0] : " started"));
		if (tryTask == null) {
			tryNext(mc);
		}
	}

	/** Saves the exact view of a tried area next to the screenshots. */
	private static void shotIso(Minecraft mc, GenerationTask task, String name) {
		try {
			var image = task.iso();
			if (image != null) {
				java.nio.file.Path out = mc.gameDirectory.toPath().resolve("screenshots").resolve("rw-try-" + name + ".png");
				image.writeToFile(out);
			}
		} catch (Exception e) {
			log("iso of " + name + " not saved: " + e);
		}
	}

	private static int widthBefore;
	private static boolean zoomShot;
	private static int chosenX = Integer.MIN_VALUE, chosenZ;
	private static int areasBefore;
	private static boolean stopping;
	private static boolean resumed;
	private static boolean libraryShown;
	private static boolean preparingShot;
	private static GenerationTask stopped;

	/**
	 * Stops the first generation once two of its pieces are built, checks the library keeps
	 * the half-built world with the rest to do, and finishes it from there. True while busy.
	 */
	private static boolean stopAndFinish(Minecraft mc, Screen screen) {
		if (resumed) {
			return false;
		}
		if (!stopping) {
			if (screen instanceof GenerationScreen gen && gen.task().pieceCount() > 2 && gen.task().piecesDone() >= 2) {
				shot(mc, "pieces-2-built");
				stopped = gen.task();
				stopped.cancel();
				stopping = true;
				return true;
			}
			return false;
		}
		if (stopped.state() == GenerationTask.State.RUNNING || !(screen instanceof LibraryScreen library)) {
			return true;
		}
		if (!libraryShown) {
			// A few frames first, so the screenshot shows the library and not the last generation frame.
			libraryShown = true;
			wait = 30;
			return true;
		}
		check(stopped.state() == GenerationTask.State.CANCELLED, "stopped: " + stopped.state());
		WorldLibrary.Entry entry = stopped.worldFolder() == null ? null : WorldLibrary.find(stopped.worldFolder());
		int left = entry == null ? -1 : entry.meta.pending.size();
		check(entry != null && entry.meta.unfinished() && left == stopped.pieceCount() - 2,
			"the stopped world is in the library with " + left + " of " + stopped.pieceCount() + " pieces to build");
		shot(mc, "library-unfinished");
		String[] error = new String[1];
		GenerationTask task = entry == null ? null : GenerationTask.resume(entry, error);
		check(task != null, "finishing started" + (error[0] != null ? " (" + error[0] + ")" : ""));
		if (task == null) {
			finish(mc);
			return true;
		}
		mc.gui.setScreen(new GenerationScreen(library, task));
		resumed = true;
		wait = 10;
		return true;
	}
	private static boolean burnChecked;

	/** Width and height of a world's picture, or zeros. */
	private static int[] picture(java.nio.file.Path world) {
		try (var image = com.mojang.blaze3d.platform.NativeImage.read(Files.readAllBytes(WorldMeta.picture(world)))) {
			return new int[] {image.getWidth(), image.getHeight()};
		} catch (Exception e) {
			return new int[] {0, 0};
		}
	}

	private static void finish(Minecraft mc) {
		log("DONE, " + failures + " failure(s), " + shots + " screenshot(s)");
		mc.stop();
	}
}
