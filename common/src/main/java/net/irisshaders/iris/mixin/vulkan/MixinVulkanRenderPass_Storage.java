package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.vulkan.VulkanStorage;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After RenderPearl pushes set 0 for a draw, set 1 (storage images / buffers) goes with it. */
@Mixin(VulkanRenderPass.class)
public class MixinVulkanRenderPass_Storage {
	@Shadow
	protected VulkanRenderPipeline pipeline;

	@Shadow
	@Final
	private VkCommandBuffer commandBuffer;

	@Inject(method = "pushDescriptors", at = @At("RETURN"))
	private void iris$bindStorageSet(CallbackInfo ci) {
		if (pipeline != null) {
			VulkanStorage.bindGraphics(commandBuffer, pipeline, pipeline.pipelineLayout());
		}
	}
}
