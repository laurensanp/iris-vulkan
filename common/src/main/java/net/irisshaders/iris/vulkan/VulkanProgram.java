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
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.api.vertex.VertexFormatElement;
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
import java.util.function.Predicate;
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
	/** Vanilla's uniform blocks that gbuffers programs read (VulkanTransformer gives them vanilla's names). */
	public static final Set<String> VANILLA_BLOCKS = Set.of("DynamicTransforms", "Projection", "Globals", "Fog", "CloudInfo", "Lighting", "u_Globals");
	/** Iris' Sodium patch declares Sodium's push constants as a GL-style block; on Vulkan they are push constants again. */
	private static final Pattern SODIUM_PUSH_CONSTANTS = Pattern.compile("layout\\s*\\(\\s*std140\\s*\\)\\s*uniform\\s+iris_SodiumPushConstants\\b");
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
	/** Vertex shader inputs in the compiled SPIR-V. */
	private final Map<String, VertexInput> vertexInputs = new HashMap<>();
	private int nextVariant;

	private VulkanProgram(int id, String name, String vertex, String fragment) {
		this.id = id;
		this.name = name;
		this.location = Identifier.fromNamespaceAndPath("iris", "vk/" + sanitize(name) + "_" + id);
		this.vertex = withPushConstants(withSeparateShaderObjects(vertex));
		this.fragment = withPushConstants(withSeparateShaderObjects(fragment));

		VulkanUniformLayout fromVertex = VulkanUniformLayout.parse(vertex);
		this.layout = fromVertex.isEmpty() ? VulkanUniformLayout.parse(fragment) : fromVertex;

		// Compiling here also reports shader errors at "link" time, where Iris expects them.
		this.liveBindings = reflectBindings();
		Set<String> samplerNames = new LinkedHashSet<>(liveBindings);
		samplerNames.remove(VulkanUniformLayout.BLOCK_NAME);
		this.samplers = List.copyOf(samplerNames);
		this.samplerUnits = new int[samplers.size()];

		this.uniforms = MemoryUtil.memCalloc(layout.size());
		layout.writeDefaults(uniforms);
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
	public void bind(RenderPass pass, List<GpuFormat> colorFormats, List<Optional<BlendFunction>> blend, List<Integer> writeMasks) {
		pass.setPipeline(pipeline(new PipelineKey(List.copyOf(colorFormats), List.copyOf(blend), List.copyOf(writeMasks))));
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

	/** Whether the compiled program reads this sampler or block. */
	public boolean binds(String name) {
		return liveBindings.contains(name);
	}

	/**
	 * Binds the uniform block and the samplers from the texture units, except {@code external} ones
	 * (set on the pass by whoever draws, e.g. vanilla's Sampler0) and vanilla's uniform blocks.
	 * For pipelines from {@link #compileGbuffer}.
	 */
	public void bindResources(RenderPass pass, Set<String> external) {
		bindUniformBlock(pass);

		for (int i = 0; i < samplers.size(); i++) {
			String sampler = samplers.get(i);

			if (external.contains(sampler) || VANILLA_BLOCKS.contains(sampler) || !liveBindings.contains(sampler)) {
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

			pass.setUniform(sampler, view, VulkanSamplers.get(VulkanUnits.sampler(unit)));
		}
	}

	/** Uploads the current uniform values and binds them as the uniform block. */
	public void bindUniformBlock(RenderPass pass) {
		if (!layout.isEmpty() && liveBindings.contains(VulkanUniformLayout.BLOCK_NAME)) {
			GpuBufferSlice slice = RenderSystem.getDevice().createCommandEncoder().transientMemory()
				.uploadStaging(uniforms.duplicate().clear().limit(layout.size()), UNIFORM_ALIGNMENT, GpuBuffer.USAGE_UNIFORM);
			pass.setUniform(VulkanUniformLayout.BLOCK_NAME, slice);
		}
	}

	/**
	 * A pipeline drawing this (gbuffers or shadow) program in place of {@code original}: same vertex layout
	 * and topology, the given color targets, depth state and culling. Output {@code i} of the fragment shader (DRAWBUFFERS
	 * entry i) goes to color target {@code outputLocations[i]}. Vertex inputs are matched to the vertex
	 * format like GL Iris binds them ("iris_" + element name, else the element name); inputs without an
	 * element read zero.
	 */
	public CompiledRenderPipeline compileGbuffer(RenderPipeline original, List<VertexFormat> vertexFormats,
												 List<ColorTargetState> colorTargets, int[] outputLocations,
												 @Nullable DepthStencilState depthState, boolean cull) {
		Set<String> matchedInputs = new HashSet<>();
		// Inputs declared wider than their element (Solas: vec4 mc_midTexCoord from 2 components): GL fills
		// in (0, 0, 1) for the rest, RenderPearl refuses the pipeline. They are read at the element's width.
		Map<String, Integer> narrowedInputs = new HashMap<>();
		List<VertexFormat> renamedFormats = new ArrayList<>(vertexFormats.size());

		for (VertexFormat format : vertexFormats) {
			if (format == null) {
				renamedFormats.add(null);
				continue;
			}

			List<String> names = new ArrayList<>();

			for (VertexFormatElement element : format.getElements()) {
				String name = element.name();

				for (String candidate : new String[]{"iris_" + element.name(), element.name()}) {
					VertexInput input = vertexInputs.get(candidate);

					if (input != null && input.baseType() == spvBaseType(element.format()) && !matchedInputs.contains(candidate)) {
						name = candidate;
						matchedInputs.add(candidate);

						if (input.components() > element.format().componentCount()) {
							narrowedInputs.put(candidate, element.format().componentCount());
							name = NARROWED_PREFIX + candidate;
						}

						break;
					}
				}

				names.add(name);
			}

			renamedFormats.add(VulkanPipelines.renamed(format, names));
		}

		Set<String> stubbed = new HashSet<>();
		String variantVertex = narrowInputs(stubInputs(vertex, input -> {
			boolean unmatched = !matchedInputs.contains(input);
			if (unmatched) stubbed.add(input);
			return unmatched;
		}), narrowedInputs);
		if (!stubbed.isEmpty()) {
			Iris.logger.info("Vulkan gbuffers: {} drawing {} has no vertex data for {} (formats {})", name, original.getLocation(), stubbed, vertexFormats);
		}
		String variantFragment = remapOutputs(fragment, outputLocations);
		Identifier variantLocation = Identifier.fromNamespaceAndPath(location.getNamespace(), location.getPath() + "/gbuffer_" + nextVariant++);

		Map<String, BindGroupLayout.UniformDescription> originalUniforms = new HashMap<>();
		BindGroupLayout.flattenUniforms(original.getBindGroupLayouts()).forEach(uniform -> originalUniforms.put(uniform.name(), uniform));
		BindGroupLayout.Builder bindings = BindGroupLayout.builder();

		for (String binding : liveBindings) {
			BindGroupLayout.UniformDescription description = originalUniforms.get(binding);

			if (description != null && description.gpuFormat() != null) {
				bindings.withUniform(binding, description.type(), description.gpuFormat());
			} else if (description != null) {
				bindings.withUniform(binding, description.type());
			} else if (binding.equals("CloudFaces")) {
				bindings.withUniform(binding, UniformType.TEXEL_BUFFER, GpuFormat.R8_SINT);
			} else if (binding.equals(VulkanUniformLayout.BLOCK_NAME) || VANILLA_BLOCKS.contains(binding)) {
				bindings.withUniform(binding, UniformType.UNIFORM_BUFFER);
			} else {
				bindings.withUniform(binding, UniformType.COMBINED_IMAGE_SAMPLER);
			}
		}

		RenderPipeline.Builder builder = RenderPipeline.builder()
			.withLocation(variantLocation)
			.withVertexShader(variantLocation)
			.withFragmentShader(variantLocation)
			.withBindGroupLayout(bindings.build())
			.withDepthStencilState(Optional.ofNullable(depthState))
			.withPolygonMode(original.getPolygonMode())
			.withCull(cull)
			.withPrimitiveTopology(original.getPrimitiveTopology())
			.withPushConstantSize(original.pushConstantSize());

		for (int slot = 0; slot < renamedFormats.size(); slot++) {
			if (renamedFormats.get(slot) != null) {
				builder.withVertexBinding(slot, renamedFormats.get(slot));
			}
		}

		for (int i = 0; i < colorTargets.size(); i++) {
			builder.withColorTargetState(i, colorTargets.get(i));
		}

		CompiledRenderPipeline pipeline = RenderSystem.getDevice()
			.compilePipeline(builder.build(), new VariantSource(variantVertex, variantFragment), Runnable::run).join().finishCompile();

		if (!(pipeline instanceof FrontendRenderPipeline)) {
			Iris.logger.error("Failed to create Vulkan gbuffers pipeline for {} (drawing {}):\n--- vertex\n{}\n--- fragment\n{}", name, original.getLocation(), variantVertex, variantFragment);
			throw new IllegalStateException("Vulkan pipeline for " + name + " failed to compile (see the log above)");
		}

		return pipeline;
	}

	private static final Pattern INPUT = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+(\\w+)\\s+(\\w+)\\s*;");
	private static final Pattern OUTPUT = Pattern.compile("layout\\s*\\(\\s*location\\s*=\\s*(\\d+)\\s*\\)(\\s*out\\b)");

	/** Turns vertex inputs that no vertex buffer element feeds into zero constants. */
	private static String stubInputs(String source, Predicate<String> unmatched) {
		Matcher matcher = INPUT.matcher(source);
		StringBuilder result = new StringBuilder();

		while (matcher.find()) {
			String type = matcher.group(1);
			String input = matcher.group(2);
			String replacement = unmatched.test(input) ? type + " " + input + " = " + type + "(0);" : matcher.group();
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);
		return result.toString();
	}

	private static final String NARROWED_PREFIX = "iris_vk_";

	/** Declares each input at {@code width} components and pads its uses back to the declared type like GL does. */
	private static String narrowInputs(String source, Map<String, Integer> widths) {
		for (Map.Entry<String, Integer> entry : widths.entrySet()) {
			String input = entry.getKey();
			Matcher declaration = Pattern.compile("(layout\\s*\\(\\s*location\\s*=\\s*\\d+\\s*\\)\\s*in\\s+)(\\w+)(\\s+)" + input + "(\\s*;)").matcher(source);

			if (!declaration.find()) {
				continue;
			}

			String type = declaration.group(2);
			String prefix = type.startsWith("u") ? "u" : type.startsWith("i") ? "i" : "";
			int declared = Character.isDigit(type.charAt(type.length() - 1)) ? type.charAt(type.length() - 1) - '0' : 1;
			int width = entry.getValue();
			String narrowType = width == 1 ? (prefix.equals("u") ? "uint" : prefix.equals("i") ? "int" : "float") : prefix + "vec" + width;
			String zero = prefix.equals("u") ? "0u" : prefix.equals("i") ? "0" : "0.0";
			String one = prefix.equals("u") ? "1u" : prefix.equals("i") ? "1" : "1.0";

			StringBuilder padded = new StringBuilder(type).append('(').append(NARROWED_PREFIX).append(input);

			for (int component = width; component < declared; component++) {
				padded.append(", ").append(component == 3 ? one : zero);
			}

			padded.append(')');

			String marker = "@@iris_vk_declaration@@";
			String newDeclaration = declaration.group(1) + narrowType + declaration.group(3) + NARROWED_PREFIX + input + declaration.group(4);
			source = source.substring(0, declaration.start()) + marker + source.substring(declaration.end());
			source = source.replaceAll("\\b" + input + "\\b", Matcher.quoteReplacement(padded.toString()));
			source = source.replace(marker, newDeclaration);
		}

		return source;
	}

	/** Moves fragment output i to location outputLocations[i]. */
	private static String remapOutputs(String source, int[] outputLocations) {
		Matcher matcher = OUTPUT.matcher(source);
		StringBuilder result = new StringBuilder();

		while (matcher.find()) {
			int output = Integer.parseInt(matcher.group(1));
			int target = output < outputLocations.length ? outputLocations[output] : -1;
			// An output without a target (a buffer the pass doesn't have) goes to an unused location; nothing reads it.
			String replacement = "layout(location = " + (target >= 0 ? target : 15 - output) + ")" + matcher.group(2);
			matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
		}

		matcher.appendTail(result);
		return result.toString();
	}

	/** SPIR-V base type a vertex element of this format is read as (RenderPearl's PipelineBuilder checks it). */
	private static int spvBaseType(GpuFormat format) {
		return switch (format.componentType()) {
			case UINT_8, UINT_16, UINT_32 -> 8;
			case SINT_8, SINT_16, SINT_32 -> 7;
			default -> 13;
		};
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

						if (type == ShaderType.VERTEX) {
							for (SpvModule.Reflection.InterfaceVariable input : module.reflect().inputs()) {
								vertexInputs.put(input.name(), new VertexInput(input.type().baseType(), Math.max(1, input.type().vectorSize())));
							}
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
			builder.withColorTargetState(i, new ColorTargetState(key.blend.get(i), key.colorFormats.get(i), key.writeMasks.get(i)));
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

	private static String withPushConstants(String source) {
		return SODIUM_PUSH_CONSTANTS.matcher(source).replaceAll("layout(push_constant) uniform iris_SodiumPushConstants");
	}

	private static String sanitize(String name) {
		return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_./-]", "_");
	}

	private record PipelineKey(List<GpuFormat> colorFormats, List<Optional<BlendFunction>> blend, List<Integer> writeMasks) {
	}

	private record VertexInput(int baseType, int components) {
	}

	/** Serves one gbuffers variant's GLSL. */
	private record VariantSource(String vertex, String fragment) implements ShaderSource {
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
