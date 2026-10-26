package net.irisshaders.iris.pathways.colorspace;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.shaderpack.preprocessor.JcppProcessor;
import net.irisshaders.iris.vulkan.VulkanDraw;
import net.irisshaders.iris.vulkan.VulkanProgram;
import net.irisshaders.iris.vulkan.VulkanSamplers;
import net.irisshaders.iris.vulkan.VulkanTextures;
import net.irisshaders.iris.vulkan.VulkanUnits;
import net.minecraft.client.Minecraft;
import org.apache.commons.io.IOUtils;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL12C;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Colour space conversion on Vulkan: the same fragment shader as ColorSpaceFragmentConverter, reading a
 * copy of the game's output and drawing the converted image back over it.
 */
public class VulkanColorSpaceConverter implements ColorSpaceConverter {
	private static final String VERTEX = """
		#version 330 core
		layout(location = 0) in vec3 Position;
		layout(location = 1) in vec2 UV0;
		layout(location = 0) out vec2 uv;
		void main() {
			gl_Position = vec4(Position.xy * 2.0 - 1.0, 0.0, 1.0);
			uv = UV0;
		}
		""";

	private ColorSpace colorSpace = ColorSpace.SRGB;
	private int program;
	private int sampler;
	private int copy;

	public VulkanColorSpaceConverter(int width, int height, ColorSpace colorSpace) {
		rebuildProgram(width, height, colorSpace);
	}

	@Override
	public void rebuildProgram(int width, int height, ColorSpace colorSpace) {
		if (program != 0) {
			VulkanProgram.delete(program);
			program = 0;
		}

		if (copy != 0) {
			VulkanTextures.delete(copy);
			copy = 0;
		}

		this.colorSpace = colorSpace;

		if (colorSpace == ColorSpace.SRGB) {
			return;
		}

		String source;

		try {
			source = new String(IOUtils.toByteArray(Objects.requireNonNull(getClass().getResourceAsStream("/colorSpace.csh"))), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		List<StringPair> defines = new ArrayList<>();
		defines.add(new StringPair("CURRENT_COLOR_SPACE", String.valueOf(colorSpace.ordinal())));

		for (ColorSpace space : ColorSpace.values()) {
			defines.add(new StringPair(space.name(), String.valueOf(space.ordinal())));
		}

		String fragment = JcppProcessor.glslPreprocessSource(source, defines)
			.replace("in vec2 uv;", "layout(location = 0) in vec2 uv;")
			.replace("out vec4 outColor;", "layout(location = 0) out vec4 outColor;");

		program = VulkanProgram.create("iris_color_space", VERTEX, null, fragment);

		if (sampler == 0) {
			sampler = VulkanSamplers.create();
			VulkanSamplers.parameter(sampler, GL11C.GL_TEXTURE_MIN_FILTER, GL11C.GL_NEAREST);
			VulkanSamplers.parameter(sampler, GL11C.GL_TEXTURE_MAG_FILTER, GL11C.GL_NEAREST);
			VulkanSamplers.parameter(sampler, GL11C.GL_TEXTURE_WRAP_S, GL12C.GL_CLAMP_TO_EDGE);
			VulkanSamplers.parameter(sampler, GL11C.GL_TEXTURE_WRAP_T, GL12C.GL_CLAMP_TO_EDGE);
		}
	}

	@Override
	public void process(GlTexture target) {
		// GL only; see processMainTarget.
	}

	/** Converts the game's main render target in place (end of the frame, like the GL converter). */
	public void processMainTarget() {
		if (colorSpace == ColorSpace.SRGB || program == 0) {
			return;
		}

		com.mojang.blaze3d.pipeline.RenderTarget main = Minecraft.getInstance().gameRenderer.mainRenderTarget();
		GpuTexture mainTexture = main.getColorTexture();
		GpuTexture copyTexture = VulkanTextures.texture(copy);

		if (copyTexture == null || copyTexture.getWidth(0) != mainTexture.getWidth(0) || copyTexture.getHeight(0) != mainTexture.getHeight(0)
			|| copyTexture.getFormat() != mainTexture.getFormat()) {
			if (copy != 0) {
				VulkanTextures.delete(copy);
			}

			copy = VulkanTextures.create("Iris color space input", GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_TEXTURE_BINDING,
				mainTexture.getFormat(), mainTexture.getWidth(0), mainTexture.getHeight(0), 1);
			copyTexture = VulkanTextures.texture(copy);
		}

		RenderSystem.getDevice().createCommandEncoder().copyTextureToTexture(mainTexture, copyTexture, 0, 0, 0, 0, 0,
			mainTexture.getWidth(0), mainTexture.getHeight(0));

		int previousSampler = VulkanUnits.sampler(0);
		VulkanUnits.overrideView(0, VulkanTextures.view(copy));
		VulkanUnits.bindSampler(0, sampler);
		VulkanProgram.get(program).uniform1i(VulkanProgram.get(program).location("readImage"), 0);

		VulkanDraw.fullscreen("color space", program, List.of(main.getColorTextureView()), 0, 0, mainTexture.getWidth(0), mainTexture.getHeight(0), null);

		VulkanUnits.overrideView(0, null);
		VulkanUnits.bindSampler(0, previousSampler);
	}
}
