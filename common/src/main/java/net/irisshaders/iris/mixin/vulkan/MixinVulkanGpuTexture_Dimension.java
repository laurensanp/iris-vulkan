package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.irisshaders.iris.vulkan.VulkanRaw;
import net.irisshaders.iris.vulkan.VulkanTextureDimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * RenderPearl makes only 2D images (arrays / cubes); shader packs also have 1D and 3D ones (custom
 * images, LUTs, voxel volumes). VulkanRaw.createTexture sets the dimension for the next texture, which
 * becomes a 1D / 3D image (depthOrLayers is then its depth) and remembers it for its views.
 */
@Mixin(VulkanGpuTexture.class)
public class MixinVulkanGpuTexture_Dimension implements VulkanTextureDimension {
	@Unique
	private int iris$dimension = 2;

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;imageType(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"))
	private int iris$imageType(int type) {
		return switch (VulkanRaw.nextDimension()) {
			case 1 -> 0; // VK_IMAGE_TYPE_1D
			case 3 -> 2; // VK_IMAGE_TYPE_3D
			default -> type;
		};
	}

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkExtent3D;set(III)Lorg/lwjgl/vulkan/VkExtent3D;"), index = 2)
	private int iris$depth(int depth) {
		return VulkanRaw.nextDimension() == 3 ? VulkanRaw.nextDepth() : depth;
	}

	@ModifyArg(method = "<init>", at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkImageCreateInfo;arrayLayers(I)Lorg/lwjgl/vulkan/VkImageCreateInfo;"))
	private int iris$arrayLayers(int layers) {
		return VulkanRaw.nextDimension() == 3 ? 1 : layers;
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void iris$rememberDimension(CallbackInfo ci) {
		this.iris$dimension = VulkanRaw.nextDimension();
	}

	@Override
	public int iris$dimension() {
		return iris$dimension;
	}
}
