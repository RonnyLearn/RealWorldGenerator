package net.ronnylearn.realworld.arnis;

import net.ronnylearn.realworld.RealWorldMod;

import java.io.IOException;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * The Arnis world generator, loaded into the game process as a native library.
 * The library ships inside the mod jar and is unpacked once into realworld/native.
 * Calls go through Java's Foreign Function &amp; Memory API, so no helper process runs.
 */
public final class ArnisLibrary {
	private static final String RESOURCE = "/natives/windows-x86_64/arnis_native.dll";
	private static ArnisLibrary instance;
	private static String loadError;

	private final MethodHandle generate;
	private final MethodHandle free;
	private final MethodHandle progress;
	private final MethodHandle message;
	private final MethodHandle cancel;
	private final MethodHandle version;
	private final MethodHandle running;
	private final MethodHandle liveInfo;
	private final MethodHandle liveCopy;
	private final MemorySegment liveInfoBuffer = Arena.global().allocate(ValueLayout.JAVA_LONG, 8);
	private final MemorySegment messageBuffer = Arena.global().allocate(1024);

	private ArnisLibrary(Path file) {
		Linker linker = Linker.nativeLinker();
		SymbolLookup lookup = SymbolLookup.libraryLookup(file, Arena.global());
		generate = linker.downcallHandle(lookup.findOrThrow("arnis_generate"),
			FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
		free = linker.downcallHandle(lookup.findOrThrow("arnis_free"),
			FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
		progress = linker.downcallHandle(lookup.findOrThrow("arnis_progress"),
			FunctionDescriptor.of(ValueLayout.JAVA_DOUBLE));
		message = linker.downcallHandle(lookup.findOrThrow("arnis_message"),
			FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
		cancel = linker.downcallHandle(lookup.findOrThrow("arnis_cancel"), FunctionDescriptor.ofVoid());
		version = linker.downcallHandle(lookup.findOrThrow("arnis_version"),
			FunctionDescriptor.of(ValueLayout.ADDRESS));
		running = linker.downcallHandle(lookup.findOrThrow("arnis_running"),
			FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN));
		liveInfo = linker.downcallHandle(lookup.findOrThrow("arnis_live_info"),
			FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS));
		liveCopy = linker.downcallHandle(lookup.findOrThrow("arnis_live_copy"),
			FunctionDescriptor.of(ValueLayout.JAVA_BOOLEAN, ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG));
	}

	/**
	 * The city being built right now: out_w, out_h, step, min_x, min_z, max_x, max_z and a
	 * version that changes as regions are written; null while there is none.
	 */
	public synchronized long[] liveInfo() {
		try {
			if (!(boolean) liveInfo.invokeExact(liveInfoBuffer)) {
				return null;
			}
			return liveInfoBuffer.toArray(ValueLayout.JAVA_LONG);
		} catch (Throwable e) {
			return null;
		}
	}

	/** Copies the city being built (ABGR colours, top heights) into native buffers of {@code len} values. */
	public boolean liveCopy(MemorySegment colors, MemorySegment heights, long len) {
		try {
			return (boolean) liveCopy.invokeExact(colors, heights, len);
		} catch (Throwable e) {
			return false;
		}
	}

	/** True while a generation runs inside the library. */
	public boolean running() {
		try {
			return (boolean) running.invokeExact();
		} catch (Throwable e) {
			return false;
		}
	}

	/** The loaded library, or null when it cannot run here; {@link #loadError()} says why. */
	public static synchronized ArnisLibrary get() {
		if (instance != null || loadError != null) {
			return instance;
		}
		try {
			if (!System.getProperty("os.name", "").toLowerCase().contains("win")
				|| !System.getProperty("os.arch", "").contains("64")) {
				throw new IOException("Arnis is bundled for 64-bit Windows only.");
			}
			instance = new ArnisLibrary(unpack());
			RealWorldMod.LOGGER.info("Arnis {} loaded", instance.version());
		} catch (Throwable e) {
			loadError = e.getMessage() == null ? e.toString() : e.getMessage();
			RealWorldMod.LOGGER.error("Could not load the Arnis library", e);
		}
		return instance;
	}

	public static synchronized String loadError() {
		return loadError;
	}

	/** Copies the library out of the jar. The file name carries a hash, so updates never fight a loaded copy. */
	private static Path unpack() throws Exception {
		byte[] bytes;
		try (InputStream in = ArnisLibrary.class.getResourceAsStream(RESOURCE)) {
			if (in == null) {
				throw new IOException("The Arnis library is missing from the mod jar.");
			}
			bytes = in.readAllBytes();
		}
		String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).substring(0, 16);
		Path dir = RealWorldMod.dataDir().resolve("native");
		Path file = dir.resolve("arnis_native-" + hash + ".dll");
		if (!Files.exists(file) || Files.size(file) != bytes.length) {
			Files.createDirectories(dir);
			Path tmp = Files.createTempFile(dir, "arnis", ".tmp");
			Files.write(tmp, bytes);
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		}
		// Older copies are left from previous versions; whatever is not locked can go.
		try (var old = Files.list(dir)) {
			old.filter(p -> !p.equals(file) && p.getFileName().toString().startsWith("arnis_native-"))
				.forEach(p -> { try { Files.deleteIfExists(p); } catch (IOException ignored) { } });
		}
		return file;
	}

	/** Runs a generation and blocks until it ends. Returns Arnis's JSON result. */
	public String generate(String requestJson) {
		try (Arena arena = Arena.ofConfined()) {
			MemorySegment request = arena.allocateFrom(requestJson, StandardCharsets.UTF_8);
			MemorySegment result = (MemorySegment) generate.invokeExact(request);
			try {
				return result.reinterpret(Integer.MAX_VALUE).getString(0, StandardCharsets.UTF_8);
			} finally {
				free.invokeExact(result);
			}
		} catch (Throwable e) {
			throw new IllegalStateException("Arnis call failed", e);
		}
	}

	/** Progress of the running generation, 0 to 100. */
	public double progress() {
		try {
			return (double) progress.invokeExact();
		} catch (Throwable e) {
			return 0;
		}
	}

	/** Arnis's own description of the current step (English). */
	public synchronized String message() {
		try {
			long length = (long) message.invokeExact(messageBuffer, messageBuffer.byteSize());
			return length == 0 ? "" : messageBuffer.getString(0, StandardCharsets.UTF_8);
		} catch (Throwable e) {
			return "";
		}
	}

	public void cancel() {
		try {
			cancel.invokeExact();
		} catch (Throwable ignored) {
		}
	}

	public String version() {
		try {
			MemorySegment text = (MemorySegment) version.invokeExact();
			return text.reinterpret(64).getString(0, StandardCharsets.UTF_8);
		} catch (Throwable e) {
			return "?";
		}
	}
}
