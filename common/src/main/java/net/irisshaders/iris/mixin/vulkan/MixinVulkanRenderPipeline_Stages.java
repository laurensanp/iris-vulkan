package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import com.mojang.renderpearl.backend.vulkan.VulkanRenderPipeline;
import net.irisshaders.iris.vulkan.VulkanRaw;
import net.irisshaders.iris.vulkan.VulkanStages;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkAllocationCallbacks;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineTessellationStateCreateInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.LongBuffer;
import java.util.List;

/**
 * Pipelines of programs with geometry / tessellation shaders get those stages (see VulkanStages).
 * With tessellation the triangles RenderPearl draws are patches of three control points, as GL Iris
 * draws GL_PATCHES in place of GL_TRIANGLES.
 */
@Mixin(VulkanRenderPipeline.class)
public class MixinVulkanRenderPipeline_Stages {
	private static final int VK_PRIMITIVE_TOPOLOGY_PATCH_LIST = 10;

	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/VK12;vkCreateDescriptorSetLayout(Lorg/lwjgl/vulkan/VkDevice;Lorg/lwjgl/vulkan/VkDescriptorSetLayoutCreateInfo;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private static int iris$stagesReadDescriptors(VkDevice device, VkDescriptorSetLayoutCreateInfo info, VkAllocationCallbacks allocator, LongBuffer layout,
												  Operation<Integer> original, @Local(argsOnly = true) BackendRenderPipeline.CreateInfo createInfo) {
		VulkanStages.Extra stages = VulkanStages.get(createInfo.name());

		if (stages != null && info.pBindings() != null) {
			for (VkDescriptorSetLayoutBinding binding : info.pBindings()) {
				binding.stageFlags(binding.stageFlags() | stages.stageFlags());
			}
		}

		return original.call(device, info, allocator, layout);
	}

	@WrapOperation(method = "compile", at = @At(value = "INVOKE",
		target = "Lorg/lwjgl/vulkan/VK12;vkCreateGraphicsPipelines(Lorg/lwjgl/vulkan/VkDevice;JLorg/lwjgl/vulkan/VkGraphicsPipelineCreateInfo$Buffer;Lorg/lwjgl/vulkan/VkAllocationCallbacks;Ljava/nio/LongBuffer;)I"))
	private static int iris$addStages(VkDevice device, long cache, VkGraphicsPipelineCreateInfo.Buffer infos, VkAllocationCallbacks allocator,
									  LongBuffer pipelines, Operation<Integer> original, @Local(argsOnly = true) BackendRenderPipeline.CreateInfo createInfo) {
		VulkanStages.Extra stages = VulkanStages.get(createInfo.name());

		if (stages == null) {
			return original.call(device, cache, infos, allocator, pipelines);
		}

		List<VulkanStages.Module> modules = VulkanStages.createModules(createInfo.name(), stages, createInfo.uniforms());

		try (MemoryStack stack = MemoryStack.stackPush()) {
			for (VkGraphicsPipelineCreateInfo info : infos) {
				VkPipelineShaderStageCreateInfo.Buffer existing = info.pStages();
				int count = info.stageCount();
				VkPipelineShaderStageCreateInfo.Buffer all = VkPipelineShaderStageCreateInfo.calloc(count + modules.size(), stack);

				MemoryUtil.memCopy(existing.address(), all.address(), (long) count * VkPipelineShaderStageCreateInfo.SIZEOF);

				for (int i = 0; i < modules.size(); i++) {
					all.get(count + i).sType$Default().stage(modules.get(i).stage()).module(modules.get(i).module()).pName(stack.UTF8("main"));
				}

				info.pStages(all);

				if (stages.tessellation()) {
					info.pInputAssemblyState().topology(VK_PRIMITIVE_TOPOLOGY_PATCH_LIST).primitiveRestartEnable(false);
					info.pTessellationState(VkPipelineTessellationStateCreateInfo.calloc(stack).sType$Default().patchControlPoints(3));
				}
			}

			return original.call(device, cache, infos, allocator, pipelines);
		} finally {
			for (VulkanStages.Module module : modules) {
				VK10.vkDestroyShaderModule(VulkanRaw.vkDevice(), module.module(), null);
			}
		}
	}
}
