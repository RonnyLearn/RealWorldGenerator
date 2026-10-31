package net.ronnylearn.realworld.mixin;

import net.minecraft.world.level.storage.LevelSummary;
import net.ronnylearn.realworld.world.WorldLibrary;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Arnis writes worlds in the pre-26.1 folder layout and leaves the move to the game. For a
 * world it has just generated, "back up before upgrading?" has nothing to protect, so the
 * game upgrades it straight away. Every other world keeps the vanilla prompt.
 */
@Mixin(LevelSummary.class)
public abstract class LevelSummaryMixin {
	@Shadow
	public abstract String getLevelId();

	@Inject(method = "backupStatus", at = @At("RETURN"), cancellable = true)
	private void realworld$skipBackupForGeneratedWorlds(CallbackInfoReturnable<LevelSummary.BackupStatus> cir) {
		if (cir.getReturnValue() == LevelSummary.BackupStatus.FILE_FIXING_REQUIRED && WorldLibrary.isRealWorld(getLevelId())) {
			cir.setReturnValue(LevelSummary.BackupStatus.NONE);
		}
	}
}
