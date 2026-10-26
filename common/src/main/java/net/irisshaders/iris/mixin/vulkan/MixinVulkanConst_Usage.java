package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import net.irisshaders.iris.vulkan.VulkanRaw;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Usage RenderPearl never asks for itself (storage images), for textures made through VulkanRaw.createTexture. */
@Mixin(VulkanConst.class)
public class MixinVulkanConst_Usage {
	@Inject(method = "textureUsageToVk", at = @At("RETURN"), cancellable = true)
	private static void iris$extraUsage(int usage, GpuFormat format, CallbackInfoReturnable<Integer> cir) {
		int extra = VulkanRaw.nextExtraUsage();

		if (extra != 0) {
			cir.setReturnValue(cir.getReturnValueI() | extra);
		}
	}
}
