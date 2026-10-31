package net.ronnylearn.realworld.world;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.ronnylearn.realworld.RealWorldMod;

import java.lang.reflect.Method;

/**
 * Whether the chunks around the player are built for drawing. Sodium (common in modpacks)
 * replaces the game's chunk renderer and builds a section only once it has been in view, so
 * its own answer is asked when it is there, section by section; without it the game's check
 * is used. Reached by reflection, so the mod needs no Sodium to run.
 */
final class Sections {
	private static boolean looked;
	private static Method instance, ready, complete;

	private Sections() {
	}

	private static Object sodium() {
		if (!looked) {
			looked = true;
			try {
				Class<?> renderer = Class.forName("net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer");
				instance = renderer.getMethod("instanceNullable");
				ready = renderer.getMethod("isSectionReady", int.class, int.class, int.class);
				complete = renderer.getMethod("isTerrainRenderComplete");
				RealWorldMod.LOGGER.info("Sodium found: chunks are checked section by section before the fly-in");
			} catch (ReflectiveOperationException | LinkageError e) {
				instance = null;
			}
		}
		if (instance == null) {
			return null;
		}
		try {
			return instance.invoke(null);
		} catch (ReflectiveOperationException e) {
			return null;
		}
	}

	/** Nothing in view is waiting to be built. */
	static boolean viewBuilt(Minecraft mc) {
		Object s = sodium();
		if (s != null) {
			try {
				return (boolean) complete.invoke(s);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		return mc.levelRenderer.hasRenderedAllSections();
	}

	/**
	 * Sections with blocks within {@code radius} chunks of the player that are not built yet,
	 * or -1 when that cannot be told (no Sodium).
	 */
	static int notBuilt(Minecraft mc, int radius) {
		Object s = sodium();
		if (s == null || mc.level == null || mc.player == null) {
			return -1;
		}
		ChunkPos c = mc.player.chunkPosition();
		int missing = 0;
		try {
			for (int dz = -radius; dz <= radius; dz++) {
				for (int dx = -radius; dx <= radius; dx++) {
					if (dx * dx + dz * dz > radius * radius) {
						continue;
					}
					int cx = c.x() + dx, cz = c.z() + dz;
					if (!mc.level.getChunkSource().hasChunk(cx, cz)) {
						continue;
					}
					LevelChunk chunk = mc.level.getChunk(cx, cz);
					LevelChunkSection[] sections = chunk.getSections();
					for (int i = 0; i < sections.length; i++) {
						if (sections[i].hasOnlyAir()) {
							continue;
						}
						int sy = mc.level.getSectionYFromSectionIndex(i);
						if (!(boolean) ready.invoke(s, cx, sy, cz)) {
							missing++;
						}
					}
				}
			}
		} catch (ReflectiveOperationException e) {
			return -1;
		}
		return missing;
	}
}
