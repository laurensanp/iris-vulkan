package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector4f;

import java.util.Arrays;

/**
 * GL's texture units and sampler bindings (glActiveTexture + glBindTexture, glBindSampler), kept as
 * plain tables; {@link VulkanProgram#bind} reads them when it binds a program's samplers.
 */
public final class VulkanUnits {
	private static final int UNITS = 32;
	private static final int[] TEXTURES = new int[UNITS];
	private static final int[] SAMPLERS = new int[UNITS];
	private static final GpuTextureView[] VIEW_OVERRIDES = new GpuTextureView[UNITS];
	private static int fallbackTexture;

	private VulkanUnits() {
	}

	public static void bindTexture(int unit, int texture) {
		if (unit >= 0 && unit < UNITS) {
			TEXTURES[unit] = texture;
		}
	}

	public static void bindSampler(int unit, int sampler) {
		if (unit >= 0 && unit < UNITS) {
			SAMPLERS[unit] = sampler;
		}
	}

	public static int texture(int unit) {
		return unit >= 0 && unit < UNITS ? TEXTURES[unit] : 0;
	}

	public static int sampler(int unit) {
		return unit >= 0 && unit < UNITS ? SAMPLERS[unit] : 0;
	}

	/** Binds a specific view (e.g. one mip level) to a unit instead of a texture's full view; null clears it. */
	public static void overrideView(int unit, @Nullable GpuTextureView view) {
		if (unit >= 0 && unit < UNITS) {
			VIEW_OVERRIDES[unit] = view;
		}
	}

	@Nullable
	public static GpuTextureView viewOverride(int unit) {
		return unit >= 0 && unit < UNITS ? VIEW_OVERRIDES[unit] : null;
	}

	public static void unbindSamplers() {
		Arrays.fill(SAMPLERS, 0);
	}

	/** What an unbound unit reads: opaque black, like an incomplete texture in core GL. */
	public static int fallbackTexture() {
		if (fallbackTexture == 0 || VulkanTextures.texture(fallbackTexture) == null) {
			fallbackTexture = VulkanTextures.create("Iris unbound texture",
				GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_RENDER_ATTACHMENT,
				GpuFormat.RGBA8_UNORM, 1, 1, 1);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(VulkanTextures.texture(fallbackTexture),
				new Vector4f(0.0F, 0.0F, 0.0F, 1.0F));
		}

		return fallbackTexture;
	}
}
