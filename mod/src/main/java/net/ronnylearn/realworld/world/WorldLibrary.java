package net.ronnylearn.realworld.world;

import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** The worlds this mod generated: every saves folder that carries realworld/meta.json. */
public final class WorldLibrary {
	private WorldLibrary() {
	}

	public static final class Entry {
		public final String folder;
		public final Path path;
		public final WorldMeta meta;
		/** Size on disk in bytes; -1 until counted. */
		public volatile long size = -1;

		Entry(String folder, Path path, WorldMeta meta) {
			this.folder = folder;
			this.path = path;
			this.meta = meta;
		}

		public long lastUsed() {
			return Math.max(meta.lastPlayed, meta.created);
		}

		/** Only Arnis One Worlds (every world made since areas could be added) can grow. */
		public boolean canExtend() {
			return Files.isRegularFile(path.resolve("arnis_one_world.json"));
		}

		/** The whole generated area seen from above. */
		public Path picture() {
			return WorldMeta.picture(path);
		}
	}

	public static Path savesDir() {
		return Minecraft.getInstance().getLevelSource().getBaseDir();
	}

	public static List<Entry> scan() {
		List<Entry> entries = new ArrayList<>();
		try (Stream<Path> dirs = Files.list(savesDir())) {
			dirs.filter(Files::isDirectory).forEach(dir -> {
				WorldMeta meta = WorldMeta.read(dir);
				if (meta != null && Files.isRegularFile(dir.resolve("level.dat"))) {
					entries.add(new Entry(dir.getFileName().toString(), dir, meta));
				}
			});
		} catch (IOException ignored) {
		}
		entries.sort(Comparator.comparingLong(Entry::lastUsed).reversed());
		for (Entry entry : entries) {
			CompletableFuture.runAsync(() -> entry.size = folderSize(entry.path));
		}
		return entries;
	}

	public static Entry find(String folder) {
		Path dir = savesDir().resolve(folder);
		WorldMeta meta = WorldMeta.read(dir);
		if (meta == null) {
			return null;
		}
		Entry entry = new Entry(folder, dir, meta);
		CompletableFuture.runAsync(() -> entry.size = folderSize(entry.path));
		return entry;
	}

	/** Whether a saves folder holds a world this mod generated. */
	public static boolean isRealWorld(String folder) {
		try {
			return Files.isRegularFile(savesDir().resolve(folder).resolve(WorldMeta.DIR).resolve("meta.json"));
		} catch (Exception e) {
			return false;
		}
	}

	public static void touch(String folder) {
		Path dir = savesDir().resolve(folder);
		WorldMeta meta = WorldMeta.read(dir);
		if (meta != null) {
			meta.lastPlayed = System.currentTimeMillis();
			try {
				meta.write(dir);
			} catch (IOException ignored) {
			}
		}
	}

	private static long folderSize(Path dir) {
		try (Stream<Path> files = Files.walk(dir)) {
			return files.filter(Files::isRegularFile).mapToLong(p -> {
				try {
					return Files.size(p);
				} catch (IOException e) {
					return 0;
				}
			}).sum();
		} catch (IOException e) {
			return 0;
		}
	}

	/** Deletes a world folder completely. */
	public static void delete(Entry entry) throws IOException {
		try (Stream<Path> files = Files.walk(entry.path)) {
			for (Path p : files.sorted(Comparator.reverseOrder()).toList()) {
				Files.deleteIfExists(p);
			}
		}
	}
}
