package net.ronnylearn.realworld.mixin;

import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.ronnylearn.realworld.world.WorldLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Arnis gives its worlds a data pack for the taller build height, which the game calls
 * "experimental settings" and asks about on every open. For the mod's own worlds that is
 * expected, so they open straight away; every other world keeps the question.
 */
@Mixin(WorldOpenFlows.class)
public class WorldOpenFlowsMixin {
	@Inject(method = "askForBackup", at = @At("HEAD"), cancellable = true)
	private void realworld$openGeneratedWorlds(LevelStorageSource.LevelStorageAccess access, boolean oldCustomized,
											   Runnable proceed, Runnable cancel, CallbackInfo ci) {
		if (WorldLibrary.isRealWorld(access.getLevelId())) {
			proceed.run();
			ci.cancel();
		}
	}
}
