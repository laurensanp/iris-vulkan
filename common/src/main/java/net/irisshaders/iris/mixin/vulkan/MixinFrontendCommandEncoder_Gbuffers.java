package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.frontend.FrontendCommandEncoder;
import net.irisshaders.iris.vulkan.VulkanGbufferPass;
import net.irisshaders.iris.vulkan.VulkanGbuffers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Vulkan gbuffers: world render passes into the main target write the shader pack's buffers instead. */
@Mixin(FrontendCommandEncoder.class)
public class MixinFrontendCommandEncoder_Gbuffers {
	@ModifyVariable(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
		at = @At("HEAD"), argsOnly = true)
	private RenderPassDescriptor iris$redirectToGbuffers(RenderPassDescriptor descriptor) {
		return VulkanGbuffers.redirect(descriptor);
	}

	@Inject(method = "createRenderPass(Lcom/mojang/renderpearl/api/commands/RenderPassDescriptor;)Lcom/mojang/renderpearl/api/commands/RenderPass;",
		at = @At("RETURN"))
	private void iris$markGbufferPass(RenderPassDescriptor descriptor, CallbackInfoReturnable<RenderPass> cir) {
		VulkanGbuffers.Target target = VulkanGbuffers.takePending();

		if (target != null && cir.getReturnValue() instanceof VulkanGbufferPass pass) {
			pass.iris$setGbufferTarget(target);
		}
	}
}
