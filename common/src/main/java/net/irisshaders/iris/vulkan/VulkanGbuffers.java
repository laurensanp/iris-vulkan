package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.vertex.VertexFormat;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.blending.BufferBlendOverride;
import net.irisshaders.iris.pipeline.IrisPipelines;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ExtendedShader;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.vertices.ImmediateState;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

/**
 * gbuffers on Vulkan (port milestone M2). On GL Iris binds the pack's framebuffer in the middle of
 * vanilla's render pass, right before each draw. A Vulkan render pass has its attachments fixed when
 * it begins, so instead:
 * <ul>
 *     <li>render passes of the world into the main target get the pack's gbuffer attachments
 *     instead: every colortex any gbuffers program writes, in buffer order, plus the main depth;</li>
 *     <li>every pipeline set in such a pass is replaced by a variant built for those attachments,
 *     from the pack's program for that draw or, without one, from the original shaders (which then
 *     write colortex0 only);</li>
 *     <li>after the pipeline is set, the program's uniform block and samplers are bound to the pass.</li>
 * </ul>
 */
public final class VulkanGbuffers {
	/** Original pipelines of compiled ones, as looked up through RenderSystem. */
	private static final Map<CompiledRenderPipeline, RenderPipeline> SOURCES = new IdentityHashMap<>();
	/** Pipelines already built for gbuffer attachments, and which program (if any) they draw with. */
	private static final Map<CompiledRenderPipeline, Variant> VARIANTS = new IdentityHashMap<>();
	private static final Map<VariantKey, CompiledRenderPipeline> VARIANT_CACHE = new HashMap<>();
	/** Names the pack's program never binds itself: vanilla (or the fallback here) sets them. */
	private static final Set<String> VANILLA_SAMPLERS = Set.of("Sampler0", "Sampler1", "Sampler2", "CloudFaces",
		"u_BlockTex", "u_LightTex", "u_SectionTimeInfo");
	/** Of those, the ones that get a white texture when the original pipeline doesn't bind them. */
	private static final Set<String> DEFAULT_WHITE = Set.of("Sampler0", "Sampler1", "Sampler2");

	@Nullable
	private static Target pending;
	/** Albedo texture (GL id) of the last uniform upload for a pack program. */
	private static int uploadedAlbedo;

	private VulkanGbuffers() {
	}

	/** The colortex buffers and views a redirected render pass writes. */
	public record Target(int[] buffers, List<GpuFormat> formats, List<Boolean> opaqueAlpha, boolean beforeTranslucent) {
	}

	/** {@code owned}: compiled here (pack programs), not by the game's pipeline cache, so closed here. */
	private record Variant(@Nullable ExtendedShader shader, boolean owned) {
	}

	private record VariantKey(RenderPipeline source, List<VertexFormat> vertexFormats, List<GpuFormat> formats,
							  List<Boolean> opaqueAlpha, @Nullable GlProgram program) {
	}

	/** Called for every lookup in RenderSystem's pipeline cache, so a compiled pipeline can be rebuilt later. */
	public static void remember(RenderPipeline source, @Nullable CompiledRenderPipeline compiled) {
		if (compiled != null && !VARIANTS.containsKey(compiled)) {
			SOURCES.put(compiled, source);
		}
	}

	/**
	 * Render passes of the world into the main target write the pack's gbuffers instead. Returns the
	 * descriptor to use; {@link #takePending()} then says whether it was redirected.
	 */
	public static RenderPassDescriptor redirect(RenderPassDescriptor descriptor) {
		pending = null;

		if (!IrisVulkan.isActive() || !(Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline)
			|| !pipeline.shouldOverrideShaders() || ShadowRenderingState.areShadowsCurrentlyBeingRendered()) {
			return descriptor;
		}

		if (descriptor.colorAttachments().size() != 1 || descriptor.colorAttachments().getFirst() == null) {
			return descriptor;
		}

		GpuTexture main = Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture();

		if (main == null || descriptor.colorAttachments().getFirst().textureView().texture() != main) {
			return descriptor;
		}

		List<GpuTextureView> views = pipeline.getVulkanGbufferViews();
		int[] buffers = pipeline.getVulkanGbufferBuffers();

		if (views.isEmpty()) {
			return descriptor;
		}

		RenderPassDescriptor.Builder builder = RenderPassDescriptor.builder(descriptor.label());
		List<GpuFormat> formats = new ArrayList<>(views.size());
		List<Boolean> opaqueAlpha = new ArrayList<>(views.size());

		for (GpuTextureView view : views) {
			opaqueAlpha.add(VulkanTextures.hasOpaqueAlpha(view.texture()));
			// Iris clears its own buffers (ClearPass); vanilla's clear of the main target stays out of them.
			builder.withColorAttachment(view, Optional.empty());
			formats.add(view.texture().getFormat());
		}

		RenderPassDescriptor.Attachment<OptionalDouble> depth = descriptor.depthAttachment();

		if (depth != null) {
			builder.withDepthAttachment(depth.textureView(), depth.clearValue());
		}

		builder.withRenderArea(descriptor.renderArea());
		pending = new Target(buffers, List.copyOf(formats), List.copyOf(opaqueAlpha), pipeline.isBeforeTranslucent);
		return builder.build();
	}

	/** The target of the render pass just created by {@link #redirect}, or null if it wasn't redirected. */
	@Nullable
	public static Target takePending() {
		Target target = pending;
		pending = null;
		return target;
	}

	/** RenderPass.setPipeline in a redirected pass: the variant of this pipeline for the gbuffer attachments. */
	public static CompiledRenderPipeline adapt(CompiledRenderPipeline compiled, Target target) {
		if (VARIANTS.containsKey(compiled)) {
			return compiled;
		}

		RenderPipeline source = SOURCES.get(compiled);

		if (source == null) {
			VulkanUnsupported.once("a pipeline that didn't come from RenderSystem in a gbuffers pass");
			return compiled;
		}

		ExtendedShader shader = null;

		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline && !ImmediateState.bypass) {
			ShaderKey key = IrisPipelines.getPipeline(pipeline, source);
			GlProgram program = key == null ? null : pipeline.getShaderMap().getShader(key);

			if (program instanceof ExtendedShader extended) {
				shader = extended;
			}
		}

		boolean skip = ImmediateState.skipExtension.get();
		// Unused vertex buffer slots are null.
		List<VertexFormat> vertexFormats = Collections.unmodifiableList(new ArrayList<>(source.getVertexFormatBindings()));
		VariantKey key = new VariantKey(source, vertexFormats, target.formats(), target.opaqueAlpha(), shader);
		CompiledRenderPipeline variant = VARIANT_CACHE.get(key);

		if (variant == null) {
			ImmediateState.skipExtension.set(true);

			try {
				if (shader != null) {
					try {
						variant = compileProgram(source, vertexFormats, shader, target);
					} catch (RuntimeException e) {
						// A pack program that doesn't build for Vulkan: draw with the game's shaders instead of crashing.
						VulkanUnsupported.once("gbuffers program for " + source.getLocation() + " (drawn with the game's shaders): " + e.getMessage());
						shader = null;
						variant = compileOriginal(source, vertexFormats, target);
					}
				} else {
					variant = compileOriginal(source, vertexFormats, target);
				}
			} finally {
				ImmediateState.skipExtension.set(skip);
			}

			VARIANT_CACHE.put(key, variant);
			VARIANTS.put(variant, new Variant(shader, shader != null));
		}

		return variant;
	}

	/** After setPipeline: the pack program's own uniforms and samplers, and defaults for vanilla's that the pipeline didn't have. */
	public static void afterSetPipeline(RenderPass pass, CompiledRenderPipeline compiled, Set<String> boundUniforms) {
		Variant variant = VARIANTS.get(compiled);

		if (variant == null || variant.shader() == null) {
			return;
		}

		ExtendedShader shader = variant.shader();
		VulkanProgram program = VulkanProgram.get(shader.getProgramId());

		shader.iris$setupState(null);
		program.bindResources(pass, VANILLA_SAMPLERS);
		shader.iris$clearState();
		uploadedAlbedo = currentAlbedo();

		if (Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline) {
			GpuTextureView white = pipeline.getWhitePixel().getTextureView();

			for (String sampler : DEFAULT_WHITE) {
				if (program.binds(sampler) && !boundUniforms.contains(sampler)) {
					pass.setUniform(sampler, white, RenderSystem.getSamplerCache().getRepeat(FilterMode.NEAREST));
				}
			}
		}

		boolean readsVanillaGlobals = program.binds("Projection") || program.binds("Fog") || program.binds("Globals");

		if (readsVanillaGlobals && (!boundUniforms.contains("Projection") || !boundUniforms.contains("Fog") || !boundUniforms.contains("Globals"))) {
			RenderSystem.bindDefaultUniforms(pass);
		}
	}

	/**
	 * RenderPass.setUniform of Sampler0 / u_BlockTex after setPipeline (Sodium binds the block atlas only
	 * then): uniforms derived from the albedo texture (atlasSize, gtextureSize...) were uploaded for the
	 * previous one, upload them again. GL Iris uploads right before the draw, so it never sees the old one.
	 */
	public static void afterAlbedoBound(RenderPass pass, @Nullable CompiledRenderPipeline compiled) {
		Variant variant = compiled == null ? null : VARIANTS.get(compiled);

		if (variant == null || variant.shader() == null || currentAlbedo() == uploadedAlbedo) {
			return;
		}

		ExtendedShader shader = variant.shader();
		shader.iris$setupState(null);
		VulkanProgram.get(shader.getProgramId()).bindUniformBlock(pass);
		shader.iris$clearState();
		uploadedAlbedo = currentAlbedo();
	}

	private static int currentAlbedo() {
		return Iris.getPipelineManager().getPipelineNullable() instanceof IrisRenderingPipeline pipeline ? pipeline.getAlbedoTex() : 0;
	}

	private static CompiledRenderPipeline compileProgram(RenderPipeline source, List<VertexFormat> vertexFormats, ExtendedShader shader, Target target) {
		VulkanProgram program = VulkanProgram.get(shader.getProgramId());
		int[] drawBuffers = shader.getDrawBuffers();
		int[] outputLocations = new int[drawBuffers.length];
		Set<Integer> written = new HashSet<>();

		for (int i = 0; i < drawBuffers.length; i++) {
			outputLocations[i] = indexOf(target.buffers(), drawBuffers[i]);

			if (outputLocations[i] >= 0) {
				written.add(outputLocations[i]);
			}
		}

		ColorTargetState original = source.getColorTargetStates().isEmpty() ? ColorTargetState.DEFAULT : source.getColorTargetStates().getFirst();
		Optional<BlendFunction> blend = original == null ? Optional.empty() : original.blendFunction();
		int writeMask = original == null ? ColorTargetState.WRITE_ALL : original.writeMask();
		BlendModeOverride override = shader.getBlendModeOverride();

		if (override != null) {
			blend = VulkanDraw.toBlendFunction(override);
		}

		List<ColorTargetState> colorTargets = new ArrayList<>(target.formats().size());

		for (int i = 0; i < target.formats().size(); i++) {
			GpuFormat format = target.formats().get(i);

			if (!written.contains(i)) {
				colorTargets.add(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_NONE));
				continue;
			}

			Optional<BlendFunction> bufferBlend = blend;

			for (BufferBlendOverride bufferOverride : shader.getBufferBlendOverrides()) {
				// Buffer blend overrides are indexed by draw buffer (output), like glBlendFunci.
				if (bufferOverride.drawBuffer() < outputLocations.length && outputLocations[bufferOverride.drawBuffer()] == i) {
					bufferBlend = VulkanDraw.toBlendFunction(bufferOverride.blendMode());
				}
			}

			colorTargets.add(new ColorTargetState(bufferBlend, format, alphaMask(target, i, writeMask)));
		}

		return program.compileGbuffer(source, vertexFormats, colorTargets, outputLocations);
	}

	/** The original shaders, writing colortex0 (attachment 0) only; compiled through the game's pipeline cache. */
	private static CompiledRenderPipeline compileOriginal(RenderPipeline source, List<VertexFormat> vertexFormats, Target target) {
		List<ColorTargetState> colorTargets = new ArrayList<>(target.formats().size());
		ColorTargetState original = source.getColorTargetStates().isEmpty() ? ColorTargetState.DEFAULT : source.getColorTargetStates().getFirst();
		int colortex0 = indexOf(target.buffers(), 0);

		for (int i = 0; i < target.formats().size(); i++) {
			GpuFormat format = target.formats().get(i);

			if (i == colortex0 && original != null) {
				colorTargets.add(new ColorTargetState(original.blendFunction(), format, alphaMask(target, i, original.writeMask())));
			} else {
				colorTargets.add(new ColorTargetState(Optional.empty(), format, ColorTargetState.WRITE_NONE));
			}
		}

		if (colortex0 != 0) {
			// The original fragment shader writes location 0 only.
			VulkanUnsupported.once("vanilla draws in gbuffers when the pack doesn't write colortex0 first");
		}

		RenderPipeline variant = VulkanPipelines.copy(source, vertexFormats, colorTargets);
		CompiledRenderPipeline compiled = RenderSystem.getCompiledPipelineNullable(variant);

		if (compiled == null) {
			throw new IllegalStateException("Failed to compile " + source.getLocation() + " for the shader pack's gbuffers");
		}

		return compiled;
	}

	/** No alpha writes into buffers whose GL format has no alpha (see VulkanTextures.keepOpaqueAlpha). */
	private static int alphaMask(Target target, int attachment, int writeMask) {
		return target.opaqueAlpha().get(attachment) ? writeMask & ~ColorTargetState.WRITE_ALPHA : writeMask;
	}

	private static int indexOf(int[] array, int value) {
		for (int i = 0; i < array.length; i++) {
			if (array[i] == value) {
				return i;
			}
		}

		return -1;
	}

	/** Pipelines are rebuilt for a new shader pack / resource reload. */
	public static void clear() {
		VARIANTS.forEach((compiled, variant) -> {
			if (variant.owned()) {
				compiled.close();
			}
		});
		VARIANTS.clear();
		VARIANT_CACHE.clear();
		SOURCES.clear();
	}
}
