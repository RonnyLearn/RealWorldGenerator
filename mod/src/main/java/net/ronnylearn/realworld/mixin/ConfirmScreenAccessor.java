package net.ronnylearn.realworld.mixin;

import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import net.minecraft.client.gui.screens.ConfirmScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the mod answer the game's "world upgrade done, join now?" question for its own worlds. */
@Mixin(ConfirmScreen.class)
public interface ConfirmScreenAccessor {
	@Accessor("callback")
	BooleanConsumer realworld$callback();
}
