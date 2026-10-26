package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.api.pipeline.PrimitiveTopology;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderSource;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.UniformType;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import net.irisshaders.iris.pathways.FullScreenQuadRenderer;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Optional;
import java.util.OptionalDouble;

/**
 * glGenerateMipmap for depth textures (shadowtex0Mipmap / shadowtex1Mipmap): each level is drawn from
 * the one above into a depth-only render pass, the fragment shader writing the average of its 2x2
 * texels (texelFetch: reading the depth view through a filtering sampler returned 0). The
 * level is cleared to 1 and the test passes everything at or below it (with ALWAYS the depth test, and
 * with it the write, may be off).
 */
public final class VulkanDepthMipmaps {
	private static final Identifier LOCATION = Identifier.fromNamespaceAndPath("iris", "vk/depth_mipmap");
	private static final String VERTEX = """
		#version 330 core
		#extension GL_ARB_separate_shader_objects : require
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		layout(location = 0) out vec2 texcoord;
		void main() {
			gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			texcoord = UV0;
		}
		""";
	private static final String FRAGMENT = """
		#version 330 core
		#extension GL_ARB_separate_shader_objects : require
		uniform sampler2D source;
		layout(location = 0) in vec2 texcoord;
		void main() {
			ivec2 texel = ivec2(gl_FragCoord.xy) * 2;
			ivec2 last = textureSize(source, 0) - 1;
			gl_FragDepth = 0.25 * (texelFetch(source, min(texel, last), 0).r + texelFetch(source, min(texel + ivec2(1, 0), last), 0).r
				+ texelFetch(source, min(texel + ivec2(0, 1), last), 0).r + texelFetch(source, min(texel + ivec2(1, 1), last), 0).r);
		}
		""";
	@Nullable
	private static CompiledRenderPipeline pipeline;

	private VulkanDepthMipmaps() {
	}

	public static void generate(int texture) {
		GpuTexture gpuTexture = VulkanTextures.texture(texture);

		if (gpuTexture == null || gpuTexture.getMipLevels() < 2) {
			return;
		}

		GpuBuffer indices = RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).getBuffer(6);

		for (int level = 1; level < gpuTexture.getMipLevels(); level++) {
			RenderPassDescriptor descriptor = RenderPassDescriptor.builder(() -> "Iris depth mipmap")
				.withDepthAttachment(VulkanTextures.levelView(texture, level), OptionalDouble.of(1.0))
				.withRenderArea(new RenderPass.RenderArea(0, 0, gpuTexture.getWidth(level), gpuTexture.getHeight(level)))
				.build();

			try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(descriptor)) {
				pass.setPipeline(pipeline());
				pass.setUniform("source", VulkanTextures.levelView(texture, level - 1), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.setVertexBuffer(0, FullScreenQuadRenderer.INSTANCE.getQuad().slice());
				pass.setIndexBuffer(indices, RenderSystem.getSequentialBuffer(PrimitiveTopology.QUADS).type());
				pass.drawIndexed(6, 1, 0, 0, 0);
			}
		}
	}

	private static CompiledRenderPipeline pipeline() {
		if (pipeline == null || pipeline.isClosed()) {
			RenderPipeline renderPipeline = RenderPipeline.builder()
				.withLocation(LOCATION)
				.withVertexShader(LOCATION)
				.withFragmentShader(LOCATION)
				.withBindGroupLayout(BindGroupLayout.builder().withUniform("source", UniformType.COMBINED_IMAGE_SAMPLER).build())
				.withVertexBinding(0, DefaultVertexFormat.POSITION_TEX)
				.withPrimitiveTopology(PrimitiveTopology.QUADS)
				.withCull(false)
				.withDepthStencilState(Optional.of(new DepthStencilState(CompareOp.LESS_THAN_OR_EQUAL, true)))
				.build();

			pipeline = RenderSystem.getDevice().compilePipeline(renderPipeline, new Source(), Runnable::run).join().finishCompile();

			if (!(pipeline instanceof FrontendRenderPipeline)) {
				throw new IllegalStateException("Vulkan depth mipmap pipeline failed to compile (see the log above)");
			}
		}

		return pipeline;
	}

	private static final class Source implements ShaderSource {
		@Override
		public String getShader(Identifier id, ShaderType type) {
			return type == ShaderType.VERTEX ? VERTEX : FRAGMENT;
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
