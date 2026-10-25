package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import com.mojang.renderpearl.frontend.shaders.GlslCompiler;
import com.mojang.renderpearl.util.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.Iris;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A pack program on Vulkan, standing in for a GL program object. It keeps the transformed GLSL,
 * a CPU copy of the {@code IrisUniforms} block and which texture unit each sampler reads, exactly
 * the state glUseProgram/glUniform would keep. Pipelines (one per attachment format + blend
 * combination) are compiled on first draw and cached.
 */
public final class VulkanProgram {
	/** Locations at or above this name samplers (index = location - base), below are block offsets. */
	public static final int SAMPLER_LOCATION_BASE = 1 << 24;

	private static final Int2ObjectOpenHashMap<VulkanProgram> PROGRAMS = new Int2ObjectOpenHashMap<>();
	// Uniform buffer offsets must be a multiple of minUniformBufferOffsetAlignment, at most 256.
	private static final int UNIFORM_ALIGNMENT = 256;
	private static int nextId = 1;
	@Nullable
	private static VulkanProgram current;

	private final int id;
	private final String name;
	private final Identifier location;
	private final String vertex;
	private final String fragment;
	private final VulkanUniformLayout layout;
	private final List<String> samplers;
	private final int[] samplerUnits;
	private final ByteBuffer uniforms;
	private final Map<PipelineKey, CompiledRenderPipeline> pipelines = new HashMap<>();
	/** Samplers and blocks present in the compiled SPIR-V; exactly what the bind group layout must name. */
	private final Set<String> liveBindings;

	private VulkanProgram(int id, String name, String vertex, String fragment) {
		this.id = id;
		this.name = name;
		this.location = Identifier.fromNamespaceAndPath("iris", "vk/" + sanitize(name) + "_" + id);
		this.vertex = withSeparateShaderObjects(vertex);
		this.fragment = withSeparateShaderObjects(fragment);

		VulkanUniformLayout fromVertex = VulkanUniformLayout.parse(vertex);
		this.layout = fromVertex.isEmpty() ? VulkanUniformLayout.parse(fragment) : fromVertex;

		// Compiling here also reports shader errors at "link" time, where Iris expects them.
		this.liveBindings = reflectBindings();
		Set<String> samplerNames = new LinkedHashSet<>(liveBindings);
		samplerNames.remove(VulkanUniformLayout.BLOCK_NAME);
		this.samplers = List.copyOf(samplerNames);
		this.samplerUnits = new int[samplers.size()];

		this.uniforms = MemoryUtil.memCalloc(layout.size());
	}

	/** glCreateProgram + attach + link: returns the program "name". */
	public static int create(String name, String vertex, @Nullable String geometry, String fragment) {
		if (geometry != null) {
			// RenderPearl only has vertex and fragment stages; geometry shaders come with the native layer (M3).
			throw new IllegalStateException("Program " + name + " uses a geometry shader, which is not supported on Vulkan yet");
		}

		int id = nextId++;
		PROGRAMS.put(id, new VulkanProgram(id, name, vertex, fragment));
		return id;
	}

	public static VulkanProgram get(int id) {
		VulkanProgram program = PROGRAMS.get(id);

		if (program == null) {
			throw new IllegalStateException("No Vulkan program with id " + id);
		}

		return program;
	}

	/** glUseProgram: uniform writes go to this program until another one is used. */
	public static void use(int id) {
		current = id == 0 ? null : get(id);
	}

	@Nullable
	public static VulkanProgram current() {
		return current;
	}

	public static void delete(int id) {
		VulkanProgram program = PROGRAMS.remove(id);

		if (program != null) {
			if (current == program) {
				current = null;
			}

			program.pipelines.values().forEach(CompiledRenderPipeline::close);
			program.pipelines.clear();
			MemoryUtil.memFree(program.uniforms);
		}
	}

	/** glGetUniformLocation: block offset, sampler slot, or -1. */
	public int location(String uniform) {
		VulkanUniformLayout.Member member = layout.get(uniform);

		if (member != null) {
			return member.offset();
		}

		int sampler = samplers.indexOf(uniform);
		return sampler >= 0 ? SAMPLER_LOCATION_BASE + sampler : -1;
	}

	public List<String> samplers() {
		return samplers;
	}

	/** The declared type of a block member as Iris' UniformType, or null if it has no equivalent. */
	@Nullable
	public net.irisshaders.iris.gl.uniform.UniformType uniformType(String uniform) {
		VulkanUniformLayout.Member member = layout.get(uniform);

		if (member == null) {
			return null;
		}

		return switch (member.type()) {
			case "float" -> net.irisshaders.iris.gl.uniform.UniformType.FLOAT;
			case "int", "bool" -> net.irisshaders.iris.gl.uniform.UniformType.INT;
			case "vec2" -> net.irisshaders.iris.gl.uniform.UniformType.VEC2;
			case "vec3" -> net.irisshaders.iris.gl.uniform.UniformType.VEC3;
			case "vec4" -> net.irisshaders.iris.gl.uniform.UniformType.VEC4;
			case "ivec2" -> net.irisshaders.iris.gl.uniform.UniformType.VEC2I;
			case "ivec3" -> net.irisshaders.iris.gl.uniform.UniformType.VEC3I;
			case "ivec4" -> net.irisshaders.iris.gl.uniform.UniformType.VEC4I;
			case "mat3", "mat3x3" -> net.irisshaders.iris.gl.uniform.UniformType.MAT3;
			case "mat4", "mat4x4" -> net.irisshaders.iris.gl.uniform.UniformType.MAT4;
			default -> null;
		};
	}

	// glUniform*: plain stores into the CPU copy of the block, at the offset handed out as location.

	public void uniform1i(int location, int value) {
		if (location >= SAMPLER_LOCATION_BASE) {
			samplerUnits[location - SAMPLER_LOCATION_BASE] = value;
		} else if (location >= 0) {
			uniforms.putInt(location, value);
		}
	}

	public void uniform1f(int location, float value) {
		if (location >= 0) {
			uniforms.putFloat(location, value);
		}
	}

	public void uniformFloats(int location, float... values) {
		if (location >= 0) {
			for (int i = 0; i < values.length; i++) {
				uniforms.putFloat(location + i * 4, values[i]);
			}
		}
	}

	public void uniformInts(int location, int... values) {
		if (location >= 0) {
			for (int i = 0; i < values.length; i++) {
				uniforms.putInt(location + i * 4, values[i]);
			}
		}
	}

	/** Column-major matrix with {@code size} columns of {@code size} rows; std140 pads columns to vec4. */
	public void uniformMatrix(int location, int size, FloatBuffer matrix) {
		if (location < 0) {
			return;
		}

		int start = matrix.position();

		for (int column = 0; column < size; column++) {
			for (int row = 0; row < size; row++) {
				uniforms.putFloat(location + column * 16 + row * 4, matrix.get(start + column * size + row));
			}
		}
	}

	public void uniformMatrix(int location, int size, float[] matrix) {
		if (location < 0) {
			return;
		}

		for (int column = 0; column < size; column++) {
			for (int row = 0; row < size; row++) {
				uniforms.putFloat(location + column * 16 + row * 4, matrix[column * size + row]);
			}
		}
	}

	/**
	 * Sets the pipeline for these attachments and binds the block and every sampler from the
	 * current texture/sampler units (see {@link VulkanUnits}).
	 */
	public void bind(RenderPass pass, List<GpuFormat> colorFormats, List<Optional<BlendFunction>> blend, int writeMask) {
		pass.setPipeline(pipeline(new PipelineKey(List.copyOf(colorFormats), List.copyOf(blend), writeMask)));
		Set<String> live = liveBindings;

		if (!layout.isEmpty() && live.contains(VulkanUniformLayout.BLOCK_NAME)) {
			GpuBufferSlice slice = RenderSystem.getDevice().createCommandEncoder().transientMemory()
				.uploadStaging(uniforms.duplicate().clear().limit(layout.size()), UNIFORM_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
			pass.setUniform(VulkanUniformLayout.BLOCK_NAME, slice);
		}

		for (int i = 0; i < samplers.size(); i++) {
			if (!live.contains(samplers.get(i))) {
				continue;
			}

			int unit = samplerUnits[i];
			GpuTextureView view = VulkanUnits.viewOverride(unit);

			if (view == null) {
				view = VulkanTextures.view(VulkanUnits.texture(unit));
			}

			if (view == null) {
				view = VulkanTextures.view(VulkanUnits.fallbackTexture());
			}

			pass.setUniform(samplers.get(i), view, VulkanSamplers.get(VulkanUnits.sampler(unit)));
		}
	}

	private CompiledRenderPipeline pipeline(PipelineKey key) {
		CompiledRenderPipeline pipeline = pipelines.get(key);

		if (pipeline == null || pipeline.isClosed()) {
			pipeline = compile(key);
			pipelines.put(key, pipeline);
		}

		return pipeline;
	}

	/**
	 * Names of the samplers and blocks in the compiled SPIR-V, in declaration order. RenderPearl
	 * wants the bind group layout to name exactly these, so both stages are compiled once and reflected.
	 */
	private Set<String> reflectBindings() {
		{
			Set<String> names = new LinkedHashSet<>();

			try (GlslCompiler compiler = new GlslCompiler(false, false)) {
				for (ShaderType type : new ShaderType[]{ShaderType.VERTEX, ShaderType.FRAGMENT}) {
					String source = type == ShaderType.VERTEX ? vertex : fragment;

					try (SpvModule module = compiler.compileToSpv(location.toString(), source, type, ShaderDefines.EMPTY, new Source())) {
						for (SpvModule.Reflection.Descriptor descriptor : module.reflect().descriptors()) {
							names.add(descriptor.name());
						}
					}
				}
			} catch (ShaderCompileException e) {
				Iris.logger.error("Failed to compile Vulkan program {}:\n--- vertex\n{}\n--- fragment\n{}", name, vertex, fragment);
				throw new IllegalStateException("Shader compilation failed for " + name + ": " + e.getMessage(), e);
			}

			return names;
		}
	}

	private CompiledRenderPipeline compile(PipelineKey key) {
		Set<String> live = liveBindings;
		BindGroupLayout.Builder bindings = BindGroupLayout.builder();

		if (!layout.isEmpty() && live.contains(VulkanUniformLayout.BLOCK_NAME)) {
			bindings.withUniform(VulkanUniformLayout.BLOCK_NAME, UniformType.UNIFORM_BUFFER);
		}

		for (String sampler : samplers) {
			if (live.contains(sampler)) {
				bindings.withUniform(sampler, UniformType.COMBINED_IMAGE_SAMPLER);
			}
		}

		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(location)
			.withVertexShader(location)
			.withFragmentShader(location)
			.withBindGroupLayout(bindings.build())
			.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
			.withPrimitiveTopology(PrimitiveTopology.QUADS)
			.withCull(false)
			.withDepthStencilState(Optional.empty());

		for (int i = 0; i < key.colorFormats.size(); i++) {
			builder.withColorTargetState(i, new ColorTargetState(key.blend.get(i), key.colorFormats.get(i), key.writeMask));
		}

		// RenderPearl logs compile errors and returns a placeholder instead of throwing.
		CompiledRenderPipeline pipeline = RenderSystem.getDevice().compilePipeline(builder.build(), new Source(), Runnable::run).join().finishCompile();

		if (!(pipeline instanceof FrontendRenderPipeline)) {
			Iris.logger.error("Failed to create Vulkan pipeline for {}:\n--- vertex\n{}\n--- fragment\n{}", name, vertex, fragment);
			throw new IllegalStateException("Vulkan pipeline for " + name + " failed to compile (see the log above)");
		}

		return pipeline;
	}

	/** Explicit varying locations in GLSL 330 need GL_ARB_separate_shader_objects (as in vanilla's shaders). */
	private static String withSeparateShaderObjects(String source) {
		if (source.contains("GL_ARB_separate_shader_objects")) {
			return source;
		}

		int versionEnd = source.indexOf('\n', source.indexOf("#version"));
		return source.substring(0, versionEnd + 1) + "#extension GL_ARB_separate_shader_objects : require\n" + source.substring(versionEnd + 1);
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
	}

	private record PipelineKey(List<GpuFormat> colorFormats, List<Optional<BlendFunction>> blend, int writeMask) {
	}

	/** Serves this program's GLSL to RenderPearl's compiler; pack includes are already resolved by Iris. */
	private final class Source implements ShaderSource {
		@Override
		public String getShader(Identifier id, ShaderType type) {
			return type == ShaderType.VERTEX ? vertex : fragment;
		}

		@Override
		public CachedIncludeSource getInclude(Identifier id) {
			return null;
		}

		@Override
		public void close() {
		}
	}
}
