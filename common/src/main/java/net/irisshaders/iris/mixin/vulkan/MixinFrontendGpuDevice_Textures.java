package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.frontend.FrontendGpuDevice;
import net.irisshaders.iris.vulkan.VulkanRaw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** RenderPearl refuses depth > 1 unless it's a cubemap; 3D textures from VulkanRaw.createTexture are fine (see MixinVulkanGpuTexture_Dimension). */
@Mixin(FrontendGpuDevice.class)
public class MixinFrontendGpuDevice_Textures {
	@Inject(method = "verifyTextureCreationArgs", at = @At("HEAD"), cancellable = true)
	private void iris$allow3D(int usage, int width, int height, int depthOrLayers, int mipLevels, CallbackInfo ci) {
		if (VulkanRaw.nextDimension() != 2) {
			ci.cancel();
		}
	}
}
