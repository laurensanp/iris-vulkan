package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BlendFactor;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.gl.blending.BlendMode;
import net.irisshaders.iris.gl.blending.BlendModeOverride;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import org.jetbrains.annotations.Nullable;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Draw calls of Iris' fullscreen passes (composite, deferred, final) on Vulkan. */
public final class VulkanDraw {
	private VulkanDraw() {
	}

	/**
	 * One fullscreen quad with the program's current uniforms and texture units into the
	 * framebuffer's color attachments, limited to the given rectangle (the pass viewport).
	 */
	public static void fullscreen(String label, int program, GlFramebuffer framebuffer, int x, int y, int width, int height,
								  @Nullable BlendModeOverride blendOverride) {
		fullscreen(label, program, framebuffer.vulkanColorViews(), x, y, width, height, blendOverride);
	}

	public static void fullscreen(String label, int program, List<GpuTextureView> views, int x, int y, int width, int height,
								  @Nullable BlendModeOverride blendOverride) {
		List<GpuFormat> formats = new ArrayList<>(views.size());
		List<Optional<BlendFunction>> blend = new ArrayList<>(views.size());
		List<Integer> writeMasks = new ArrayList<>(views.size());
		Optional<BlendFunction> passBlend = toBlendFunction(blendOverride);

		RenderPassDescriptor.Builder descriptor = RenderPassDescriptor.builder(() -> "Iris " + label);

		for (GpuTextureView view : views) {
			descriptor.withColorAttachment(view);
			formats.add(view.texture().getFormat());
			blend.add(passBlend);
			writeMasks.add(VulkanTextures.writeMask(view.texture(), ColorTargetState.WRITE_ALL));
		}

		// GL viewports start at the bottom left, like RenderPearl's render areas (images are stored bottom row first).
		descriptor.withRenderArea(new RenderPass.RenderArea(x, y, width, height));

		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor.build())) {
			VulkanProgram.get(program).bind(pass, formats, blend, writeMasks);
			pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
			pass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
			// indexCount, instanceCount, firstIndex, vertexOffset, firstInstance (vkCmdDrawIndexed order)
			pass.drawIndexed(6, 1, 0, 0, 0);
		}
	}

	private static final String BLIT_VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		layout(location = 0) out vec2 texcoord;
		void main() {
			gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			texcoord = UV0;
		}
		""";
	private static final String BLIT_FRAGMENT = """
		#version 330 core
		uniform sampler2D source;
		layout(location = 0) in vec2 texcoord;
		layout(location = 0) out vec4 color;
		void main() {
			color = texture(source, texcoord);
		}
		""";
	private static int blitProgram;
	private static int blitSampler;

	/** Copies {@code source} into {@code target} through a shader, so formats and sizes may differ. */
	public static void blit(GpuTexture source, GpuTexture target) {
		blit(VulkanTextures.view(VulkanTextures.idOf(source)), VulkanTextures.levelView(VulkanTextures.idOf(target), 0),
			target.getWidth(0), target.getHeight(0));
	}

	/** glGenerateMipmap: each level is a linear downsample of the one above. */
	public static void generateMipmaps(int texture) {
		GpuTexture gpuTexture = VulkanTextures.texture(texture);

		if (gpuTexture == null) {
			return;
		}

		for (int level = 1; level < gpuTexture.getMipLevels(); level++) {
			blit(VulkanTextures.levelView(texture, level - 1), VulkanTextures.levelView(texture, level),
				gpuTexture.getWidth(level), gpuTexture.getHeight(level));
		}
	}

	private static void blit(GpuTextureView source, GpuTextureView target, int width, int height) {
		if (blitProgram == 0) {
			blitProgram = VulkanProgram.create("iris_blit", BLIT_VERTEX, null, BLIT_FRAGMENT);
			blitSampler = VulkanSamplers.create();
			VulkanSamplers.parameter(blitSampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_LINEAR);
			VulkanSamplers.parameter(blitSampler, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
			VulkanSamplers.parameter(blitSampler, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
		}

		int previousSampler = VulkanUnits.sampler(0);
		VulkanUnits.overrideView(0, source);
		VulkanUnits.bindSampler(0, blitSampler);

		fullscreen("blit", blitProgram, List.of(target), 0, 0, width, height, null);

		VulkanUnits.overrideView(0, null);
		VulkanUnits.bindSampler(0, previousSampler);
	}

	public static Optional<BlendFunction> toBlendFunction(@Nullable BlendModeOverride override) {
		return toBlendFunction(override == null ? null : override.getBlendMode());
	}

	public static Optional<BlendFunction> toBlendFunction(@Nullable BlendMode mode) {
		if (mode == null) {
			return Optional.empty();
		}

		return Optional.of(new BlendFunction(factor(mode.srcRgb()), factor(mode.dstRgb()), factor(mode.srcAlpha()), factor(mode.dstAlpha())));
	}

	private static BlendFactor factor(int gl) {
		return switch (gl) {
			case GL11C.GL_ZERO -> BlendFactor.ZERO;
			case GL11C.GL_ONE -> BlendFactor.ONE;
			case GL11C.GL_SRC_COLOR -> BlendFactor.SRC_COLOR;
			case GL11C.GL_ONE_MINUS_SRC_COLOR -> BlendFactor.ONE_MINUS_SRC_COLOR;
			case GL11C.GL_DST_COLOR -> BlendFactor.DST_COLOR;
			case GL11C.GL_ONE_MINUS_DST_COLOR -> BlendFactor.ONE_MINUS_DST_COLOR;
			case GL11C.GL_SRC_ALPHA -> BlendFactor.SRC_ALPHA;
			case GL11C.GL_ONE_MINUS_SRC_ALPHA -> BlendFactor.ONE_MINUS_SRC_ALPHA;
			case GL11C.GL_DST_ALPHA -> BlendFactor.DST_ALPHA;
			case GL11C.GL_ONE_MINUS_DST_ALPHA -> BlendFactor.ONE_MINUS_DST_ALPHA;
			case GL11C.GL_SRC_ALPHA_SATURATE -> BlendFactor.SRC_ALPHA_SATURATE;
			default -> throw new IllegalArgumentException("Unknown GL blend factor " + gl);
		};
	}
}
