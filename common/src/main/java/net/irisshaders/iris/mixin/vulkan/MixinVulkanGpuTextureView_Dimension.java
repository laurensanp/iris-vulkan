package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import net.irisshaders.iris.vulkan.VulkanTextureDimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/** Views of 1D / 3D textures (see MixinVulkanGpuTexture_Dimension) get the matching view type. */
@Mixin(VulkanGpuTextureView.class)
public class MixinVulkanGpuTextureView_Dimension {
	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageViewCreateInfo;viewType(I)Lorg/lwjgl/vulkan/VkImageViewCreateInfo;"))
	private int iris$viewType(int type, @Local(argsOnly = true) VulkanGpuTexture texture) {
		return switch (((VulkanTextureDimension) texture).iris$dimension()) {
			case 1 -> 0; // VK_IMAGE_VIEW_TYPE_1D
			case 3 -> 2; // VK_IMAGE_VIEW_TYPE_3D
			default -> type;
		};
	}
}
