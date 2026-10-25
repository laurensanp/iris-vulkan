package net.irisshaders.iris.targets;

import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.opengl.GlStateManager;
import net.irisshaders.iris.gl.GLDebug;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanFormats;
import net.irisshaders.iris.vulkan.VulkanTextures;
import org.joml.Vector2i;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL43C;

import java.nio.ByteBuffer;

public class RenderTarget {
	private static final ByteBuffer NULL_BUFFER = null;
	// Written by passes, sampled by later passes, copied for the depth/pre-translucent copies.
	private static final int VULKAN_USAGE = GpuTexture.USAGE_RENDER_ATTACHMENT | GpuTexture.USAGE_TEXTURE_BINDING
		| GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_COPY_DST;
	private final InternalTextureFormat internalFormat;
	private final PixelFormat format;
	private final PixelType type;
	private final int mainTexture;
	private final int altTexture;
	private int width;
	private int height;
	private boolean isValid;
	private String name;
	private boolean allowsLinear;
	private boolean mipmapsOnAlt;
	private boolean mipmapsOnMain;

	public RenderTarget(Builder builder) {
		this.isValid = true;

		this.name = builder.name;
		this.internalFormat = builder.internalFormat;
		this.format = builder.format;
		this.type = builder.type;

		this.width = builder.width;
		this.height = builder.height;


		boolean isPixelFormatInteger = builder.internalFormat.getPixelFormat().isInteger();
		this.allowsLinear = !isPixelFormatInteger;

		if (IrisVulkan.isActive()) {
			// Filtering and wrapping live in Iris' samplers (GlSampler), not in the texture.
			this.mainTexture = VulkanTextures.create(label(false), VULKAN_USAGE, VulkanFormats.toRenderTarget(internalFormat), width, height, vulkanMipLevels(width, height));
			this.altTexture = VulkanTextures.create(label(true), VULKAN_USAGE, VulkanFormats.toRenderTarget(internalFormat), width, height, vulkanMipLevels(width, height));
			keepVulkanAlpha();
			return;
		}

		this.mainTexture = GlStateManager._genTexture();
		this.altTexture = GlStateManager._genTexture();

		setupTexture(mainTexture, builder.width, builder.height, !isPixelFormatInteger, false);
		setupTexture(altTexture, builder.width, builder.height, !isPixelFormatInteger, true);

		// Clean up after ourselves
		// This is strictly defensive to ensure that other buggy code doesn't tamper with our textures
		GlStateManager._bindTexture(0);
	}

	public static Builder builder() {
		return new Builder();
	}

	private void setupTexture(int texture, int width, int height, boolean allowsLinear, boolean alt) {
		resizeTexture(texture, width, height, alt);

		IrisRenderSystem.texParameteri(texture, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MIN_FILTER, allowsLinear ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
		IrisRenderSystem.texParameteri(texture, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_MAG_FILTER, allowsLinear ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
		IrisRenderSystem.texParameteri(texture, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_S, GL13C.GL_CLAMP_TO_EDGE);
		IrisRenderSystem.texParameteri(texture, GL11C.GL_TEXTURE_2D, GL11C.GL_TEXTURE_WRAP_T, GL13C.GL_CLAMP_TO_EDGE);
	}

	private void resizeTexture(int texture, int width, int height, boolean alt) {
		IrisRenderSystem.texImage2D(texture, GL11C.GL_TEXTURE_2D, 0, internalFormat.getGlFormat(), width, height, 0, format.getGlFormat(), type.getGlFormat(), NULL_BUFFER);

		if (name != null) {
			GLDebug.nameObject(GL43C.GL_TEXTURE, texture, name + " " + (alt ? "alt" : "main"));
		}
	}

	void resize(Vector2i textureScaleOverride) {
		this.resize(textureScaleOverride.x, textureScaleOverride.y);
	}

	// Package private, call CompositeRenderTargets#resizeIfNeeded instead.
	void resize(int width, int height) {
		requireValid();

		this.width = width;
		this.height = height;

		if (IrisVulkan.isActive()) {
			VulkanTextures.replace(mainTexture, label(false), VULKAN_USAGE, VulkanFormats.toRenderTarget(internalFormat), width, height, vulkanMipLevels(width, height));
			VulkanTextures.replace(altTexture, label(true), VULKAN_USAGE, VulkanFormats.toRenderTarget(internalFormat), width, height, vulkanMipLevels(width, height));
			keepVulkanAlpha();
			return;
		}

		resizeTexture(mainTexture, width, height, false);

		resizeTexture(altTexture, width, height, true);
	}

	public InternalTextureFormat getInternalFormat() {
		return internalFormat;
	}

	public int getMainTexture() {
		requireValid();

		return mainTexture;
	}

	public int getAltTexture() {
		requireValid();

		return altTexture;
	}

	public int getWidth() {
		return width;
	}

	public int getHeight() {
		return height;
	}

	public void destroy() {
		requireValid();
		isValid = false;

		if (IrisVulkan.isActive()) {
			VulkanTextures.delete(mainTexture);
			VulkanTextures.delete(altTexture);
			return;
		}

		GlStateManager._deleteTexture(mainTexture);
		GlStateManager._deleteTexture(altTexture);
	}

	/** Full mip chain on Vulkan (filled on demand by generateMipmaps); integer formats can't be filtered down. */
	private int vulkanMipLevels(int width, int height) {
		return allowsLinear ? 32 - Integer.numberOfLeadingZeros(Math.max(width, height)) : 1;
	}

	private void keepVulkanAlpha() {
		if (VulkanFormats.addsAlpha(internalFormat)) {
			VulkanTextures.keepOpaqueAlpha(mainTexture);
			VulkanTextures.keepOpaqueAlpha(altTexture);
		}
	}

	private String label(boolean alt) {
		return "Iris " + (name != null ? name : "colortex") + " " + (alt ? "alt" : "main");
	}

	private void requireValid() {
		if (!isValid) {
			throw new IllegalStateException("Attempted to use a deleted composite render target");
		}
	}

	public GlSampler getAltSampler() {
		if (mipmapsOnAlt) {
			return allowsLinear ? GlSampler.MIPPED_LINEAR : GlSampler.MIPPED_NEAREST;
		}
		return allowsLinear ? GlSampler.LINEAR : GlSampler.NEAREST;
	}

	public GlSampler getMainSampler() {
		if (mipmapsOnMain) {
			return allowsLinear ? GlSampler.MIPPED_LINEAR : GlSampler.MIPPED_NEAREST;
		}
		return allowsLinear ? GlSampler.LINEAR : GlSampler.NEAREST;
	}

	public void turnOnMips(boolean alt) {
		if (alt) {
			this.mipmapsOnAlt = true;
		} else {
			this.mipmapsOnMain = true;
		}
	}

	public void turnOffMips(boolean alt) {
		if (alt) {
			this.mipmapsOnAlt = false;
		} else {
			this.mipmapsOnMain = false;
		}
	}

	public static class Builder {
		private InternalTextureFormat internalFormat = InternalTextureFormat.RGBA8;
		private int width = 0;
		private int height = 0;
		private PixelFormat format = PixelFormat.RGBA;
		private PixelType type = PixelType.UNSIGNED_BYTE;
		private String name = null;

		private Builder() {
			// No-op
		}

		public Builder setName(String name) {
			this.name = name;

			return this;
		}

		public Builder setInternalFormat(InternalTextureFormat format) {
			this.internalFormat = format;

			return this;
		}

		public Builder setDimensions(int width, int height) {
			if (width <= 0) {
				throw new IllegalArgumentException("Width must be greater than zero");
			}

			if (height <= 0) {
				throw new IllegalArgumentException("Height must be greater than zero");
			}

			this.width = width;
			this.height = height;

			return this;
		}

		public Builder setPixelFormat(PixelFormat pixelFormat) {
			this.format = pixelFormat;

			return this;
		}

		public Builder setPixelType(PixelType pixelType) {
			this.type = pixelType;

			return this;
		}

		public RenderTarget build() {
			return new RenderTarget(this);
		}
	}
}
