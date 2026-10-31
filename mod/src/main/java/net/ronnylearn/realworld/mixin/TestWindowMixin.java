package net.ronnylearn.realworld.mixin;

import com.mojang.renderpearl.backend.opengl.GlBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.io.File;

/**
 * Self-test runs only (rw-selftest.flag in the game folder): the game window is created
 * hidden, so the automated test never takes focus from the player. Normal launches are untouched.
 */
@Mixin({GlBackend.class, VulkanBackend.class})
public class TestWindowMixin {
	private static final long SDL_WINDOW_HIDDEN = 0x8L;

	@ModifyArg(
		method = "createWindow",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/sdl/SDLVideo;SDL_CreateWindow(Ljava/lang/CharSequence;IIJ)J"),
		index = 3
	)
	private long realworld$hideTestWindow(long flags) {
		return new File("rw-selftest.flag").exists() ? flags | SDL_WINDOW_HIDDEN : flags;
	}
}
