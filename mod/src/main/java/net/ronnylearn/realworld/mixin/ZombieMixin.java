package net.ronnylearn.realworld.mixin;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.zombie.Zombie;
import net.ronnylearn.realworld.world.ZombieDirector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * In an apocalypse world with "don't burn" on, zombies walk in daylight. Since 26.x the
 * daylight burning of every undead mob is decided in Mob.isSunBurnTick.
 */
@Mixin(Mob.class)
public class ZombieMixin {
	@Inject(method = "isSunBurnTick", at = @At("HEAD"), cancellable = true)
	private void realworld$noBurn(CallbackInfoReturnable<Boolean> cir) {
		if ((Object) this instanceof Zombie && ZombieDirector.noBurn()) {
			cir.setReturnValue(false);
		}
	}
}
