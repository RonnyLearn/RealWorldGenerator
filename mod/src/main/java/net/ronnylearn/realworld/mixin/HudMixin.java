package net.ronnylearn.realworld.mixin;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.ronnylearn.realworld.world.CinematicEntry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** The entry's dark title card is drawn over the world while a Real World opens. */
@Mixin(Hud.class)
public class HudMixin {
	@Inject(method = "extractRenderState", at = @At("RETURN"))
	private void realworld$entryOverlay(GuiGraphicsExtractor graphics, DeltaTracker deltaTracker, CallbackInfo ci) {
		CinematicEntry.drawOverlay(graphics);
	}
}
