package net.irisshaders.iris.mixin;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.common.BaseGpuTexture;
import net.irisshaders.iris.mixinterface.GpuTextureInterface;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanTextures;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(BaseGpuTexture.class)
public abstract class MixinGpuTexture2 implements GpuTextureInterface {
	@Override
	public int iris$getGlId() {
		if (IrisVulkan.isActive()) {
			// Vulkan textures have no GL name; hand out a stable id from the Vulkan texture table.
			return VulkanTextures.idOf((GpuTexture) (Object) this);
		}

		throw new AssertionError("Why.");
	}

	@Override
	public void iris$markMipmapNonLinear() {
		if (IrisVulkan.isActive()) {
			// Filtering is sampler state on Vulkan; the texture itself has nothing to change.
			return;
		}

		throw new AssertionError("Why.");
	}
}
