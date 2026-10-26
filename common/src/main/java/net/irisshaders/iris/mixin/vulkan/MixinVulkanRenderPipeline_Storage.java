package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.vulkan.VulkanStorage;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.LongBuffer;

/** Pipelines whose shaders use storage images / buffers get descriptor set 1 in their layout (see VulkanStorage). */
@Mixin(VulkanRenderPipeline.class)
public class MixinVulkanRenderPipeline_Storage {
	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/VK12;vkCreatePipelineLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkPipelineLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private static int iris$addStorageSet(VkDevice device, VkPipelineLayoutCreateInfo info, VkAllocationCallbacks allocator, LongBuffer layout,
										  Operation<Integer> original, @Local(argsOnly = true) BackendRenderPipeline.CreateInfo createInfo) {
		if (!VulkanStorage.usesStorage(createInfo.name())) {
			return original.call(device, info, allocator, layout);
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer previous = info.pSetLayouts();
			LongBuffer sets = stack.callocLong(VulkanStorage.SET + 1);

			if (previous != null && previous.remaining() > 0) {
				sets.put(0, previous.get(previous.position()));
			}

			sets.put(VulkanStorage.SET, VulkanStorage.setLayout());
			info.pSetLayouts(sets);
			int result = original.call(device, info, allocator, layout);
			info.pSetLayouts(previous);
			return result;
		}
	}

	@Inject(method = "compile", at = @At("RETURN"))
	private static void iris$rememberStoragePipeline(VulkanDevice device, BackendRenderPipeline.CreateInfo createInfo,
													 CallbackInfoReturnable<VulkanRenderPipeline> cir) {
		if (VulkanStorage.usesStorage(createInfo.name())) {
			VulkanStorage.markCompiled(cir.getReturnValue());
		}
	}
}
