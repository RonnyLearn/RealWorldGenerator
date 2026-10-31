package net.ronnylearn.realworld.arnis;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.ronnylearn.realworld.RealWorldMod;
import net.ronnylearn.realworld.geo.GeoBox;
import net.ronnylearn.realworld.map.MapSnapshot;
import net.ronnylearn.realworld.world.IsoRender;
import net.ronnylearn.realworld.world.WorldLibrary;
import net.ronnylearn.realworld.world.WorldMeta;
import net.ronnylearn.realworld.world.WorldPicture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * One world being generated: first a picture of the area, then Arnis on a worker thread,
 * piece by piece for a big area, then the mod's own files in the world. The screens only
 * read its state. After every piece the world is complete as far as it goes and records the
 * pieces still to build, so a stopped generation can be finished later.
 */
public final class GenerationTask {
	public enum State { RUNNING, DONE, FAILED, CANCELLED }

	/** Arnis's progress bands (see progress.rs) with the mod's names for them. */
	public enum Stage {
		PREPARE(0), DOWNLOAD(1), TERRAIN(10), PROCESS(18.5), BUILD(20), GROUND(70), SAVE(90), FINISH(97);

		public final double from;

		Stage(double from) {
			this.from = from;
		}

		public String key() {
			return "realworld.stage." + name().toLowerCase();
		}

		public static Stage at(double progress) {
			Stage stage = PREPARE;
			for (Stage s : values()) {
				if (progress >= s.from) {
					stage = s;
				}
			}
			return stage;
		}
	}

	private static GenerationTask current;

	public final GeoBox box;
	public final GenerationSettings settings;
	public final long startedAt = System.currentTimeMillis();
	private final ArnisLibrary arnis;
	private final List<GeoBox> pieces;
	/** Pieces of the same generation built before this task (when finishing a stopped one). */
	private final int piecesBefore;
	private final int piecesTotal;
	private volatile int piecesDone;
	private volatile State state = State.RUNNING;
	private volatile NativeImage snapshot;
	/** The city as it is being built; its newest picture is {@link #liveFrame}. */
	private volatile net.ronnylearn.realworld.world.LiveCity live;
	private net.ronnylearn.realworld.world.IsoRender.Raster liveFrame;
	private int liveSerial;
	/** Pictures made from Arnis's live data (not from finished pieces), for the self-test. */
	private volatile int liveUpdates;
	/** The generated area, block for block, in isometric view; ready when the task is DONE. */
	private volatile NativeImage iso;
	private volatile String error;
	private volatile String worldFolder;
	private volatile long finishedAt;
	private volatile boolean cancelRequested;
	/** Arnis keeps the last run's progress until the next run starts, so it is read only while one runs. */
	private volatile boolean pieceStarted;
	/** The whole generation's progress never goes back between pieces. */
	private volatile double shownProgress;

	private GenerationTask(GeoBox box, GenerationSettings settings, ArnisLibrary arnis, List<GeoBox> pieces, int before, int total) {
		this.box = box;
		this.settings = settings;
		this.arnis = arnis;
		this.pieces = pieces;
		this.piecesBefore = before;
		this.piecesTotal = total;
	}

	public static GenerationTask current() {
		return current;
	}

	/** Set once the player has been told how a generation ended. */
	private volatile boolean announced;

	/**
	 * A generation left running in the background (the player is in the menus or in another
	 * world) says when it is over with a toast; its own screen shows that itself.
	 */
	public static void notifyWhenDone(net.minecraft.client.Minecraft mc) {
		GenerationTask task = current;
		if (task == null || task.announced || task.state == State.RUNNING) {
			return;
		}
		task.announced = true;
		if (mc.gui.screen() instanceof net.ronnylearn.realworld.screen.GenerationScreen || task.state == State.CANCELLED) {
			return;
		}
		String key = task.state == State.DONE ? "realworld.toast.done" : "realworld.toast.failed";
		net.minecraft.client.gui.components.toasts.SystemToast.add(mc.gui.toastManager(),
			net.minecraft.client.gui.components.toasts.SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
			net.minecraft.network.chat.Component.translatable(key),
			net.minecraft.network.chat.Component.literal(task.settings.worldName));
	}

	/** Starts a generation; only one runs at a time. Returns null with the reason in {@code error[0]}. */
	public static GenerationTask start(GeoBox box, GenerationSettings settings, String[] error) {
		ArnisLibrary arnis = available(error);
		if (arnis == null) {
			return null;
		}
		GenerationSettings copy = settings.copy();
		if (!copy.extend) {
			copy.folder = freeFolder(copy.worldName);
		}
		double lat = copy.spawnLat != null ? copy.spawnLat : box.centerLat();
		double lng = copy.spawnLng != null ? copy.spawnLng : box.centerLng();
		List<GeoBox> pieces = Pieces.split(box, copy.scale, Pieces.pieceKm2(copy.load), lat, lng);
		return launch(new GenerationTask(box, copy, arnis, pieces, 0, pieces.size()));
	}

	/** Builds the pieces a stopped generation left, into the same world and with the same settings. */
	public static GenerationTask resume(WorldLibrary.Entry entry, String[] error) {
		WorldMeta meta = entry.meta;
		if (!meta.unfinished()) {
			error[0] = "nothing to finish";
			return null;
		}
		ArnisLibrary arnis = available(error);
		if (arnis == null) {
			return null;
		}
		GenerationSettings copy = meta.pendingSettings.copy();
		copy.folder = entry.folder;
		copy.extend = true;
		copy.worldName = meta.name;
		copy.place = meta.place;
		List<GeoBox> pieces = new ArrayList<>();
		GeoBox all = null;
		for (double[] p : meta.pending) {
			GeoBox b = new GeoBox(p[0], p[1], p[2], p[3]);
			pieces.add(b);
			all = all == null ? b : union(all, b);
		}
		int total = Math.max(meta.pendingTotal, pieces.size());
		// The whole area again, so the pieces built before the stop are shown with the rest.
		GeoBox area = meta.pendingBox != null && meta.pendingBox.length == 4
			? new GeoBox(meta.pendingBox[0], meta.pendingBox[1], meta.pendingBox[2], meta.pendingBox[3]) : all;
		return launch(new GenerationTask(area, copy, arnis, pieces, total - pieces.size(), total));
	}

	private static ArnisLibrary available(String[] error) {
		if (current != null && current.state == State.RUNNING) {
			error[0] = "busy";
			return null;
		}
		ArnisLibrary arnis = ArnisLibrary.get();
		if (arnis == null) {
			error[0] = ArnisLibrary.loadError();
			return null;
		}
		return arnis;
	}

	private static GenerationTask launch(GenerationTask task) {
		current = task;
		Thread thread = new Thread(task::run, "RealWorld-Arnis");
		thread.setDaemon(true);
		thread.setPriority(task.settings.load.lowPriority ? Thread.MIN_PRIORITY + 1 : Thread.NORM_PRIORITY - 1);
		thread.start();
		return task;
	}

	private void run() {
		try {
			// A run that was just stopped may still be unwinding inside Arnis.
			while (arnis.running() && !cancelRequested) {
				Thread.sleep(100);
			}
			try {
				snapshot = MapSnapshot.render(box);
			} catch (Exception e) {
				RealWorldMod.LOGGER.warn("Area picture failed, generating without it: {}", e.toString());
			}
			Path saves = WorldLibrary.savesDir();
			startLive(saves.resolve(settings.folder));
			// Areas the world had before this run; the exact view shows only what this run built.
			int areasBefore = settings.extend ? WorldPicture.areas(saves.resolve(settings.folder)).size() : 0;
			Path world = null;
			for (int i = 0; i < pieces.size(); i++) {
				if (cancelRequested) {
					finish(State.CANCELLED, null);
					return;
				}
				GeoBox piece = pieces.get(i);
				GenerationSettings s = settings.copy();
				// After the first piece every piece is added to the world it made.
				s.extend = settings.extend || i > 0;
				if (s.extend) {
					s.spawnLat = null;
					s.spawnLng = null;
				}
				JsonObject request = new JsonObject();
				request.addProperty("name", s.folder);
				JsonArray args = new JsonArray();
				s.toArgs(piece, saves).forEach(args::add);
				request.add("args", args);
				request.addProperty("threads", s.load.threads());
				request.addProperty("low_priority", s.load.lowPriority);
				RealWorldMod.LOGGER.info("Generating piece {}/{} {} with Arnis ({} load): {}",
					piecesBefore + i + 1, piecesTotal, piece.toArnis(), s.load, args);

				pieceStarted = true;
				JsonObject result = JsonParser.parseString(arnis.generate(request.toString())).getAsJsonObject();
				if (result.has("cancelled") && result.get("cancelled").getAsBoolean()) {
					finish(State.CANCELLED, null);
					return;
				}
				if (!result.get("ok").getAsBoolean()) {
					String detail = result.has("detail") ? result.get("detail").getAsString() : "";
					finish(State.FAILED, result.get("error").getAsString() + (detail.isBlank() ? "" : " (" + detail + ")"));
					return;
				}
				world = Path.of(result.get("world").getAsString());
				writeWorldFiles(world, result, i, pieces.subList(i + 1, pieces.size()));
				worldFolder = world.getFileName().toString();
				// The live data of a piece is gone once it is saved; its preview takes over.
				List<WorldPicture.Area> areas = WorldPicture.areas(world);
				if (!areas.isEmpty() && live != null) {
					live.addPreview(areas.getLast());
					publishLive();
				}
				piecesDone = i + 1;
			}
			if (world != null) {
				renderIso(world, areasBefore);
				// What this run took on this computer, for the next estimates.
				double built = box.areaKm2() * settings.scale * settings.scale * piecesDone / (double) Math.max(1, piecesTotal);
				SpeedModel.record(built, elapsedMillis() / 1000.0, piecesDone, settings.load);
				// The last picture is a detailed one, to zoom into on the finished screen.
				net.ronnylearn.realworld.world.LiveCity city = live;
				if (city != null) {
					publish(city.renderDetailed(WorldPicture.areas(world)));
				}
			}
			finish(State.DONE, null);
		} catch (Throwable e) {
			RealWorldMod.LOGGER.error("Generation failed", e);
			finish(State.FAILED, e.getMessage() == null ? e.toString() : e.getMessage());
		}
	}

	/**
	 * Starts drawing the city as it is built: the street map first, then whatever Arnis has
	 * written, checked a little more than once a second on a thread of its own.
	 */
	private void startLive(Path worldDir) {
		live = new net.ronnylearn.realworld.world.LiveCity(box, snapshot, worldDir);
		// What the world already has inside the area (pieces built before a stop) shows from the start.
		if (settings.extend) {
			for (WorldPicture.Area a : WorldPicture.areas(worldDir)) {
				live.addPreview(a);
			}
		}
		publishLive();
		Thread thread = new Thread(() -> {
			try {
				while (state == State.RUNNING) {
					Thread.sleep(700);
					net.ronnylearn.realworld.world.LiveCity city = live;
					if (city != null && state == State.RUNNING && city.pollLive(arnis)) {
						liveUpdates++;
						publishLive();
					}
				}
			} catch (InterruptedException ignored) {
			} catch (Throwable e) {
				RealWorldMod.LOGGER.warn("Live city view stopped: {}", e.toString());
			} finally {
				net.ronnylearn.realworld.world.LiveCity city = live;
				if (city != null) {
					city.close();
				}
			}
		}, "RealWorld-LiveCity");
		thread.setDaemon(true);
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.start();
	}

	private void publishLive() {
		net.ronnylearn.realworld.world.LiveCity city = live;
		if (city == null) {
			return;
		}
		publish(city.render());
	}

	private void publish(net.ronnylearn.realworld.world.IsoRender.Raster next) {
		synchronized (this) {
			if (liveFrame != null) {
				liveFrame.image().close();
			}
			liveFrame = next;
			liveSerial++;
		}
	}

	public int liveUpdates() {
		return liveUpdates;
	}

	/** Serial number of the newest live picture; it changes whenever a new one is ready. */
	public synchronized int liveSerial() {
		return liveSerial;
	}

	/** A copy of the newest live picture (the caller owns its image), or null. */
	public synchronized net.ronnylearn.realworld.world.IsoRender.Raster copyLiveFrame() {
		if (liveFrame == null) {
			return null;
		}
		NativeImage source = liveFrame.image();
		NativeImage copy = new NativeImage(source.getWidth(), source.getHeight(), true);
		copy.copyFrom(source);
		return new net.ronnylearn.realworld.world.IsoRender.Raster(copy, liveFrame.planeTop(), liveFrame.planeWidth(), liveFrame.planeHeight());
	}

	/**
	 * Updates the world's own files after a piece: its meta (with the pieces still to build),
	 * picture and icon. The first piece of a new world creates them.
	 */
	private void writeWorldFiles(Path world, JsonObject result, int index, List<GeoBox> remaining) throws Exception {
		Files.createDirectories(WorldMeta.dir(world));
		boolean extended = result.has("extended") && result.get("extended").getAsBoolean();
		WorldMeta meta = extended ? WorldMeta.read(world) : null;
		boolean created = !settings.extend;
		if (meta == null) {
			meta = new WorldMeta();
			meta.name = settings.worldName;
			meta.place = settings.place;
			meta.scale = settings.scale;
			meta.terrain = settings.mode.terrain();
			meta.mode = settings.mode.name();
			meta.created = System.currentTimeMillis();
			meta.worldType = settings.flatSurroundings ? "flat" : "void";
			if (result.has("spawn") && result.get("spawn").isJsonArray()) {
				JsonArray s = result.getAsJsonArray("spawn");
				meta.spawn = new int[] {s.get(0).getAsInt(), s.get(1).getAsInt(), s.get(2).getAsInt()};
			}
		} else if (index == 0 && piecesBefore == 0 && settings.extend) {
			// A new area added to the world; its further pieces belong to the same area.
			meta.areaCount++;
			meta.lastExtended = System.currentTimeMillis();
		}
		if (created) {
			meta.generationSeconds = (int) ((System.currentTimeMillis() - startedAt) / 1000);
		}
		// The latest choices describe the world from now on (zombies apply to all of it).
		meta.profile = settings.profile.name();
		meta.interiors |= settings.interiors;
		meta.winter |= settings.winter;
		meta.apocalypse |= settings.apocalypse;
		meta.signage = settings.signage.name();
		meta.caves |= settings.caves;
		meta.heightMultiplier = settings.heightMultiplier;
		meta.zombies = settings.zombies.copy();
		meta.arnisVersion = arnis.version();

		meta.pending = new ArrayList<>();
		for (GeoBox b : remaining) {
			meta.pending.add(new double[] {b.minLat(), b.minLng(), b.maxLat(), b.maxLng()});
		}
		meta.pendingTotal = remaining.isEmpty() ? 0 : piecesTotal;
		meta.pendingBox = remaining.isEmpty() ? null : new double[] {box.minLat(), box.minLng(), box.maxLat(), box.maxLng()};
		meta.pendingSettings = remaining.isEmpty() ? null : settings.copy();

		NativeImage picture = snapshot;
		if (picture != null && created && index == 0) {
			picture.writeToFile(WorldMeta.mapImage(world));
		}
		WorldPicture.rebuild(world);

		// Bounds of everything generated so far, in blocks and in degrees.
		List<WorldPicture.Area> areas = WorldPicture.areas(world);
		int[] blocks = WorldPicture.bounds(areas);
		if (blocks != null) {
			meta.area = blocks;
			GeoBox all = null;
			for (WorldPicture.Area a : areas) {
				GeoBox b = new GeoBox(a.minLat(), a.minLng(), a.maxLat(), a.maxLng());
				all = all == null ? b : union(all, b);
			}
			meta.setBounds(all);
		} else if (result.has("area")) {
			JsonArray a = result.getAsJsonArray("area");
			meta.area = new int[] {a.get(0).getAsInt(), a.get(1).getAsInt(), a.get(2).getAsInt(), a.get(3).getAsInt()};
			meta.setBounds(box);
		}
		meta.write(world);
	}

	private static GeoBox union(GeoBox a, GeoBox b) {
		return GeoBox.of(Math.min(a.minLat(), b.minLat()), Math.min(a.minLng(), b.minLng()),
			Math.max(a.maxLat(), b.maxLat()), Math.max(a.maxLng(), b.maxLng()));
	}

	/** A saves folder name for a new world: the world name made file-safe, numbered if taken. */
	private static String freeFolder(String name) {
		String base = name.replaceAll("[\\\\/:*?\"<>|]", "_").replaceAll("\\.+$", "").trim();
		if (base.isEmpty()) {
			base = "Real World";
		}
		Path saves = WorldLibrary.savesDir();
		String folder = base;
		for (int n = 2; Files.exists(saves.resolve(folder)); n++) {
			folder = base + " (" + n + ")";
		}
		return folder;
	}

	private void finish(State state, String error) {
		this.error = error;
		this.finishedAt = System.currentTimeMillis();
		this.state = state;
	}

	/**
	 * Stops at once: the player is let go straight away while Arnis unwinds out of its work in
	 * the background. Pieces already finished stay in the world.
	 */
	public void cancel() {
		cancelRequested = true;
		arnis.cancel();
		if (state == State.RUNNING) {
			finish(State.CANCELLED, null);
		}
	}

	public State state() {
		return state;
	}

	/** Progress of the piece being built, 0 to 100. */
	public double pieceProgress() {
		if (state == State.DONE) {
			return 100;
		}
		if (!pieceStarted || !arnis.running()) {
			return 0;
		}
		return Math.max(0, Math.min(100, arnis.progress()));
	}

	/** Progress of the whole generation, 0 to 100. */
	public double progress() {
		if (state == State.DONE) {
			return 100;
		}
		// Counted over the whole generation, so finishing a stopped one goes on from where it was.
		int done = piecesBefore + piecesDone;
		double inPiece = piecesDone < pieces.size() ? pieceProgress() / 100 : 0;
		double p = Math.min(100, (done + inPiece) * 100 / piecesTotal);
		if (p > shownProgress) {
			shownProgress = p;
		}
		return shownProgress;
	}

	/** Pieces in the whole generation, counting any built before a stop. */
	public int pieceCount() {
		return piecesTotal;
	}

	/** The piece being built, from 1. */
	public int pieceNumber() {
		return Math.min(piecesTotal, piecesBefore + piecesDone + 1);
	}

	/** Pieces this task has finished. */
	public int piecesDone() {
		return piecesDone;
	}

	public String arnisMessage() {
		return pieceStarted ? arnis.message() : "";
	}

	private void renderIso(Path world, int areasBefore) {
		List<WorldPicture.Area> areas = WorldPicture.areas(world);
		if (areas.size() <= areasBefore) {
			return;
		}
		try {
			NativeImage image = IsoRender.render(areas.subList(areasBefore, areas.size()));
			if (image != null) {
				image.writeToFile(WorldMeta.dir(world).resolve("iso.png"));
				iso = image;
			}
		} catch (Exception e) {
			RealWorldMod.LOGGER.warn("Isometric view failed: {}", e.toString());
		}
	}

	public NativeImage iso() {
		return iso;
	}

	public NativeImage snapshot() {
		return snapshot;
	}

	public String error() {
		return error;
	}

	public String worldFolder() {
		return worldFolder;
	}

	public long elapsedMillis() {
		return (finishedAt > 0 ? finishedAt : System.currentTimeMillis()) - startedAt;
	}

	/** Frees the area picture once no screen needs it. */
	public void dispose() {
		NativeImage image = snapshot;
		snapshot = null;
		if (image != null) {
			image.close();
		}
		NativeImage exact = iso;
		iso = null;
		if (exact != null) {
			exact.close();
		}
		synchronized (this) {
			if (liveFrame != null && state != State.RUNNING) {
				liveFrame.image().close();
				liveFrame = null;
			}
		}
		if (current == this && state != State.RUNNING) {
			current = null;
		}
	}
}
