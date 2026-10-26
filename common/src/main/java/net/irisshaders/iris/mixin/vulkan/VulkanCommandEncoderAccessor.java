package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Raw Vulkan work (compute, image clears) is recorded into the game's own command buffer, between its passes. */
@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {
	/** The frame's command buffer being recorded (allocated on first use). */
	@Invoker("commandBuffer")
	VkCommandBuffer iris$commandBuffer();

	@Accessor("currentRenderPass")
	VulkanRenderPass iris$currentRenderPass();
}
