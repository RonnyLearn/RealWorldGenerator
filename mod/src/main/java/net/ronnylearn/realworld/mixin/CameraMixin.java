package net.ronnylearn.realworld.mixin;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import net.ronnylearn.realworld.world.CinematicEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * While a Real World is entered the game looks through the mod's own camera entity; it is put
 * on this frame's point of the fly-in just before the camera follows it.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private float eyeHeight;
	@Shadow
	private float eyeHeightOld;

	@Inject(method = "update", at = @At("HEAD"))
	private void realworld$flyIn(DeltaTracker deltaTracker, CallbackInfo ci) {
		CinematicEntry.positionRig(deltaTracker.getGameTimeDeltaPartialTick(true));
		// The camera eases its eye height towards a new entity's over several ticks; when the
		// view is handed between the rig and the player that would sink and rise the view.
		if (CinematicEntry.takeEyeSnap()) {
			Entity entity = Minecraft.getInstance().getCameraEntity();
			eyeHeight = eyeHeightOld = entity == null ? 0f : entity.getEyeHeight();
		}
	}
}
