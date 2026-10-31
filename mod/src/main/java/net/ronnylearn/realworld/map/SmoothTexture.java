package net.ronnylearn.realworld.map;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.minecraft.client.renderer.texture.DynamicTexture;

/** A texture sampled with linear filtering: map tiles and photos are drawn at fractional scales. */
public final class SmoothTexture extends DynamicTexture {
	public SmoothTexture(String label, NativeImage image) {
		super(() -> label, image);
		sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
	}
}
