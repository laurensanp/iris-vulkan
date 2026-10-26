package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.irisshaders.iris.Iris;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.KHRPushDescriptor;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A compute pipeline in raw Vulkan (RenderPearl has none): the program's GLSL compiled with shaderc,
 * its uniform block and samplers as push descriptors in set 0 (like RenderPearl's own pipelines) and
 * storage images / buffers in set 1 (VulkanStorage). Dispatches go into the frame's command buffer
 * between RenderPearl's passes, with a full barrier on each side, matching GL's glMemoryBarrier use.
 */
public final class VulkanCompute {
	private static final int VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER = 1;
	private static final int VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER = 6;
	private static final int VK_SHADER_STAGE_COMPUTE_BIT = 0x20;
	private static final int VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR = 1;
	private static final int VK_PIPELINE_BIND_POINT_COMPUTE = 1;

	/** Set 0 binding of each sampler / the uniform block, in reflection order. */
	private final String name;
	private final Map<String, Integer> bindings;
	private final boolean storage;
	private final long setLayout;
	private final long pipelineLayout;
	private final long pipeline;

	private VulkanCompute(String name, Map<String, Integer> bindings, boolean storage, long setLayout, long pipelineLayout, long pipeline) {
		this.name = name;
		this.bindings = bindings;
		this.storage = storage;
		this.setLayout = setLayout;
		this.pipelineLayout = pipelineLayout;
		this.pipeline = pipeline;
	}

	/** Compiles and reflects; throws with the shaderc message on errors (Iris reports it like a link error). */
	public static VulkanCompute create(String name, String source, boolean storage) {
		ByteBuffer spv = compile(name, source, Shaderc.shaderc_compute_shader);

		try {
			Map<String, Integer> bindings = reflect(spv);

			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkDescriptorSetLayoutBinding.Buffer layoutBindings = VkDescriptorSetLayoutBinding.calloc(bindings.size(), stack);
				int i = 0;

				for (Map.Entry<String, Integer> binding : bindings.entrySet()) {
					layoutBindings.get(i++)
						.binding(binding.getValue())
						.descriptorType(isBlock(binding.getKey()) ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER : VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
						.descriptorCount(1)
						.stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
				}

				LongBuffer handle = stack.callocLong(1);
				check(VK10.vkCreateDescriptorSetLayout(VulkanRaw.vkDevice(), VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
					.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR).pBindings(layoutBindings), null, handle), name);
				long setLayout = handle.get(0);

				LongBuffer sets = storage ? stack.longs(setLayout, VulkanStorage.setLayout()) : stack.longs(setLayout);
				check(VK10.vkCreatePipelineLayout(VulkanRaw.vkDevice(), VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(sets), null, handle), name);
				long pipelineLayout = handle.get(0);

				check(VK10.vkCreateShaderModule(VulkanRaw.vkDevice(), VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spv), null, handle), name);
				long module = handle.get(0);

				VkComputePipelineCreateInfo.Buffer info = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default().layout(pipelineLayout);
				info.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(module).pName(stack.UTF8("main"));
				int result = VK10.vkCreateComputePipelines(VulkanRaw.vkDevice(), 0L, info, null, handle);
				VK10.vkDestroyShaderModule(VulkanRaw.vkDevice(), module, null);
				check(result, name);

				return new VulkanCompute(name, bindings, storage, setLayout, pipelineLayout, handle.get(0));
			}
		} finally {
			MemoryUtil.memFree(spv);
		}
	}

	public boolean binds(String name) {
		return bindings.containsKey(name);
	}

	public List<String> names() {
		return List.copyOf(bindings.keySet());
	}

	/**
	 * One dispatch: {@code block} is the uploaded uniform block (null without one), {@code samplers} the
	 * view + sampler of each sampler name, {@code storageSet} the program's set 1.
	 */
	public void dispatch(VkCommandBuffer commandBuffer, Map<String, long[]> resources, long storageSet, int x, int y, int z,
						 long indirectBuffer, long indirectOffset) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanRaw.checkpoint(commandBuffer, true, "Iris compute " + name);
			VulkanRaw.barrier(commandBuffer);
			VK10.vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);

			int count = (int) bindings.keySet().stream().filter(resources::containsKey).count();

			if (count > 0) {
				VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(count, stack);
				int i = 0;

				for (Map.Entry<String, Integer> binding : bindings.entrySet()) {
					long[] resource = resources.get(binding.getKey());

					if (resource == null) {
						continue;
					}

					VkWriteDescriptorSet write = writes.get(i++).sType$Default().dstBinding(binding.getValue()).descriptorCount(1);

					if (isBlock(binding.getKey())) {
						write.descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
							.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(resource[0]).offset(resource[1]).range(resource[2]));
					} else {
						write.descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
							.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(resource[0]).sampler(resource[1]).imageLayout(VK10.VK_IMAGE_LAYOUT_GENERAL));
					}
				}

				KHRPushDescriptor.vkCmdPushDescriptorSetKHR(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, writes);
			}

			if (storage && storageSet != 0L) {
				VK10.vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, VulkanStorage.SET, stack.longs(storageSet), null);
			}

			if (indirectBuffer != 0L) {
				VK10.vkCmdDispatchIndirect(commandBuffer, indirectBuffer, indirectOffset);
			} else {
				VK10.vkCmdDispatch(commandBuffer, Math.max(0, x), Math.max(0, y), Math.max(0, z));
			}

			VulkanRaw.barrier(commandBuffer);
			VulkanRaw.checkpoint(commandBuffer, false, "Iris compute " + name);
		}
	}

	/** Destroyed once the frames that may still run it are done. */
	public void close() {
		long pipeline = this.pipeline;
		long pipelineLayout = this.pipelineLayout;
		long setLayout = this.setLayout;

		VulkanRaw.encoder().queueForDestroy((Destroyable) () -> {
			VK10.vkDestroyPipeline(VulkanRaw.vkDevice(), pipeline, null);
			VK10.vkDestroyPipelineLayout(VulkanRaw.vkDevice(), pipelineLayout, null);
			VK10.vkDestroyDescriptorSetLayout(VulkanRaw.vkDevice(), setLayout, null);
		});
	}

	private static boolean isBlock(String name) {
		return name.equals(VulkanUniformLayout.BLOCK_NAME);
	}

	/** GLSL to SPIR-V for Vulkan 1.2 ({@code kind}: a shaderc_*_shader); set 0 bindings are assigned by shaderc. */
	static ByteBuffer compile(String name, String source, int kind) {
		long compiler = Shaderc.shaderc_compiler_initialize();
		long options = Shaderc.shaderc_compile_options_initialize();

		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan, Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", options);

			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					String message = Shaderc.shaderc_result_get_error_message(result);
					Iris.logger.error("Failed to compile Vulkan shader {}:\n{}\n--- source\n{}", name, message, source);
					throw new IllegalStateException("Shader compilation failed for " + name + ": " + message);
				}

				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				copy.put(bytes).flip();
				return copy;
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
			Shaderc.shaderc_compiler_release(compiler);
		}
	}

	/** Set 0 descriptors by name (set 1 is hidden by MixinSPIRVModule_Storage). */
	/**
	 * Gives every set 0 descriptor its own binding, in reflection order, and writes them into {@code spv}.
	 * shaderc assigns bindings only to resources the shader uses; declared but unused samplers would all
	 * sit at binding 0 next to the uniform block. RenderPearl renumbers its pipelines' descriptors the same way.
	 */
	private static Map<String, Integer> reflect(ByteBuffer spv) {
		ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining());
		copy.put(spv.duplicate()).flip();
		Map<String, Integer> bindings = new LinkedHashMap<>();

		try (SpvModule module = new SPIRVModule(copy, ShaderType.FRAGMENT)) {
			for (SpvModule.Reflection.Descriptor descriptor : module.reflect().descriptors()) {
				if (!bindings.containsKey(descriptor.name())) {
					bindings.put(descriptor.name(), bindings.size());
				}

				descriptor.binding(bindings.get(descriptor.name()));
				descriptor.descriptorSetIndex(0);
			}

			MemoryUtil.memCopy(MemoryUtil.memAddress(module.spv()), MemoryUtil.memAddress(spv), spv.remaining());
		} catch (ShaderCompileException e) {
			throw new IllegalStateException("Couldn't reflect compute shader: " + e.getMessage(), e);
		}

		return bindings;
	}

	private static void check(int result, String name) {
		if (result != VK10.VK_SUCCESS) {
			throw new IllegalStateException("Vulkan: creating the compute pipeline for " + name + " failed (" + result + ")");
		}
	}
}
