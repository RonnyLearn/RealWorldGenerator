package net.ronnylearn.realworld.world;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.ronnylearn.realworld.RealWorldMod;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.ronnylearn.realworld.map.SmoothTexture;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** PNG files from disk as textures for the library cards, loaded in the background. */
public final class Pictures {
	public record Picture(Identifier id, int width, int height) {
	}

	private static final Map<String, Picture> LOADED = new HashMap<>();
	private static final Map<String, Boolean> PENDING = new HashMap<>();
	private static int counter;

	private Pictures() {
	}

	/** The picture when loaded, else null (loading starts). The key includes the file time, so a new photo shows up. */
	public static Picture get(Path file) {
		String key;
		try {
			if (!Files.isRegularFile(file)) {
				return null;
			}
			key = file.toAbsolutePath() + "@" + Files.getLastModifiedTime(file).toMillis();
		} catch (Exception e) {
			return null;
		}
		Picture picture = LOADED.get(key);
		if (picture != null || PENDING.containsKey(key)) {
			return picture;
		}
		PENDING.put(key, true);
		CompletableFuture.runAsync(() -> {
			try {
				NativeImage image = NativeImage.read(Files.readAllBytes(file));
				Minecraft.getInstance().execute(() -> {
					Identifier id = Identifier.fromNamespaceAndPath(RealWorldMod.MOD_ID, "picture/" + (counter++));
					boolean blocks = file.getFileName().toString().equals("blocks.png");
					Minecraft.getInstance().getTextureManager().register(id, blocks
						? new DynamicTexture(() -> "Real World block map", image)
						: new SmoothTexture("Real World picture", image));
					LOADED.put(key, new Picture(id, image.getWidth(), image.getHeight()));
				});
			} catch (Exception e) {
				RealWorldMod.LOGGER.warn("Could not read {}: {}", file, e.toString());
			}
		});
		return null;
	}

	public static void releaseAll() {
		for (Picture picture : LOADED.values()) {
			Minecraft.getInstance().getTextureManager().release(picture.id());
		}
		LOADED.clear();
		PENDING.clear();
	}
}
