package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import com.mojang.renderpearl.util.ShaderCompileException;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.shaderc.Shaderc;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Geometry and tessellation shaders on Vulkan. RenderPearl builds pipelines from a vertex and a
 * fragment shader only; for a program with more stages it still gets those two, and when it creates
 * the Vulkan pipeline (MixinVulkanRenderPipeline_Stages) the other stages are added, compiled here
 * with the set 0 bindings RenderPearl gave the same names. Its check that vertex outputs match
 * fragment inputs is skipped for such pipelines (MixinPipelineBuilder_Stages): stages sit in between.
 */
public final class VulkanStages {
	public static final int VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT = 0x2;
	public static final int VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT = 0x4;
	public static final int VK_SHADER_STAGE_GEOMETRY_BIT = 0x8;
	/** Pipeline name (its location) to the extra stages' GLSL. */
	private static final Map<String, Extra> PIPELINES = new ConcurrentHashMap<>();

	private VulkanStages() {
	}

	/** The GLSL of the stages beyond vertex and fragment; null where a stage is absent. */
	public record Extra(@Nullable String tessControl, @Nullable String tessEval, @Nullable String geometry) {
		public boolean isEmpty() {
			return tessControl == null && tessEval == null && geometry == null;
		}

		public boolean tessellation() {
			return tessEval != null;
		}

		/** The stage flags these stages add (for descriptor visibility). */
		public int stageFlags() {
			return (tessControl != null ? VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT : 0)
				| (tessEval != null ? VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT : 0)
				| (geometry != null ? VK_SHADER_STAGE_GEOMETRY_BIT : 0);
		}
	}

	/** A compiled extra stage: its Vulkan stage bit and shader module. */
	public record Module(int stage, long module) {
	}

	/** Called before RenderPearl compiles a pipeline of this name. */
	public static void register(String pipelineName, Extra stages) {
		PIPELINES.put(pipelineName, stages);
	}

	@Nullable
	public static Extra get(String pipelineName) {
		return PIPELINES.get(pipelineName);
	}

	/**
	 * Shader modules of the extra stages for this pipeline: every sampler / block they read at the
	 * binding of the uniform of that name ({@code uniforms} is RenderPearl's set 0, binding = index).
	 */
	public static List<Module> createModules(String pipelineName, Extra stages, List<BindGroupLayout.UniformDescription> uniforms) {
		List<Module> modules = new ArrayList<>(3);

		if (stages.tessControl() != null) {
			modules.add(new Module(VK_SHADER_STAGE_TESSELLATION_CONTROL_BIT,
				createModule(pipelineName + "/tess_control", stages.tessControl(), Shaderc.shaderc_tess_control_shader, uniforms)));
		}

		if (stages.tessEval() != null) {
			modules.add(new Module(VK_SHADER_STAGE_TESSELLATION_EVALUATION_BIT,
				createModule(pipelineName + "/tess_eval", stages.tessEval(), Shaderc.shaderc_tess_evaluation_shader, uniforms)));
		}

		if (stages.geometry() != null) {
			modules.add(new Module(VK_SHADER_STAGE_GEOMETRY_BIT,
				createModule(pipelineName + "/geometry", stages.geometry(), Shaderc.shaderc_geometry_shader, uniforms)));
		}

		return modules;
	}

	private static long createModule(String name, String source, int kind, List<BindGroupLayout.UniformDescription> uniforms) {
		try (SpvModule module = new SPIRVModule(VulkanCompute.compile(name, source, kind), ShaderType.FRAGMENT);
			 MemoryStack stack = MemoryStack.stackPush()) {
			for (SpvModule.Reflection.Descriptor descriptor : module.reflect().descriptors()) {
				int binding = -1;

				for (int i = 0; i < uniforms.size(); i++) {
					if (uniforms.get(i).name().equals(descriptor.name())) {
						binding = i;
						break;
					}
				}

				if (binding < 0) {
					throw new IllegalStateException(name + " reads " + descriptor.name() + ", which the pipeline doesn't bind");
				}

				descriptor.binding(binding);
				descriptor.descriptorSetIndex(0);
			}

			LongBuffer handle = stack.callocLong(1);
			int result = VK10.vkCreateShaderModule(VulkanRaw.vkDevice(), VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(module.spv()), null, handle);

			if (result != VK10.VK_SUCCESS) {
				throw new IllegalStateException("Vulkan: creating the shader module for " + name + " failed (" + result + ")");
			}

			return handle.get(0);
		} catch (ShaderCompileException e) {
			throw new IllegalStateException("Couldn't reflect " + name + ": " + e.getMessage(), e);
		}
	}

	/** The names of the samplers and blocks the extra stages read (for the program's bind group layout). */
	public static List<String> descriptorNames(String name, Extra stages) {
		List<String> names = new ArrayList<>();
		addNames(names, name + "/tess_control", stages.tessControl(), Shaderc.shaderc_tess_control_shader);
		addNames(names, name + "/tess_eval", stages.tessEval(), Shaderc.shaderc_tess_evaluation_shader);
		addNames(names, name + "/geometry", stages.geometry(), Shaderc.shaderc_geometry_shader);
		return names;
	}

	private static void addNames(List<String> names, String name, @Nullable String source, int kind) {
		if (source == null) {
			return;
		}

		try (SpvModule module = new SPIRVModule(VulkanCompute.compile(name, source, kind), ShaderType.FRAGMENT)) {
			module.reflect().descriptors().forEach(descriptor -> names.add(descriptor.name()));
		} catch (ShaderCompileException e) {
			throw new IllegalStateException("Couldn't reflect " + name + ": " + e.getMessage(), e);
		}
	}
}
