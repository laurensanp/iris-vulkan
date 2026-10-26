package net.irisshaders.iris.gl.texture;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.sampler.GlSampler;
import net.irisshaders.iris.shaderpack.texture.TextureFilteringData;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanTextures;
import net.irisshaders.iris.vulkan.VulkanUnsupported;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.function.IntSupplier;

public class GlTexture extends GlResource implements TextureAccess {
	private final TextureType target;
	private final boolean shouldBlur;
	private final boolean shouldClamp;

	public GlTexture(TextureType target, int sizeX, int sizeY, int sizeZ, int internalFormat, int format, int pixelType, byte[] pixels, TextureFilteringData filteringData) {
		super(IrisVulkan.isActive() ? createVulkan(target, sizeX, sizeY, sizeZ, internalFormat, format, pixelType, pixels) : GlStateManager._genTexture());
		this.target = target;
		this.shouldBlur = filteringData.shouldBlur();
		this.shouldClamp = filteringData.shouldClamp();

		if (IrisVulkan.isActive()) {
			// Uploaded in createVulkan; filtering and wrapping come from getSampling().
			return;
		}

		IrisRenderSystem.bindTextureForSetup(target.getGlType(), getGlId());

		TextureUploadHelper.resetTextureUploadState();

		ByteBuffer buffer = MemoryUtil.memAlloc(pixels.length);
		buffer.put(pixels);
		buffer.flip();
		target.apply(this.getGlId(), sizeX, sizeY, sizeZ, internalFormat, format, pixelType, buffer);
		MemoryUtil.memFree(buffer);

		int texture = this.getGlId();

		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_MIN_FILTER, filteringData.shouldBlur() ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_MAG_FILTER, filteringData.shouldBlur() ? GL11C.GL_LINEAR : GL11C.GL_NEAREST);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_WRAP_S, filteringData.shouldClamp() ? GL13C.GL_CLAMP_TO_EDGE : GL13C.GL_REPEAT);

		if (sizeY > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), GL11C.GL_TEXTURE_WRAP_T, filteringData.shouldClamp() ? GL13C.GL_CLAMP_TO_EDGE : GL13C.GL_REPEAT);
		}

		if (sizeZ > 0) {
			IrisRenderSystem.texParameteri(texture, target.getGlType(), GL30C.GL_TEXTURE_WRAP_R, filteringData.shouldClamp() ? GL13C.GL_CLAMP_TO_EDGE : GL13C.GL_REPEAT);
		}

		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL20C.GL_TEXTURE_MAX_LEVEL, 0);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL20C.GL_TEXTURE_MIN_LOD, 0);
		IrisRenderSystem.texParameteri(texture, target.getGlType(), GL20C.GL_TEXTURE_MAX_LOD, 0);
		IrisRenderSystem.texParameterf(texture, target.getGlType(), GL20C.GL_TEXTURE_LOD_BIAS, 0.0F);

		IrisRenderSystem.bindTextureForSetup(target.getGlType(), 0);
	}

	/**
	 * Raw texture files (shaders.properties "texture.<stage>.<name> = file.dat TEXTURE_3D ...") on Vulkan:
	 * a 1D / 2D / 3D texture with the pixels converted from GL's layout. Id 0 (the sampler then reads the
	 * fallback texture) for formats that have no Vulkan equivalent here.
	 */
	private static int createVulkan(TextureType target, int sizeX, int sizeY, int sizeZ, int internalFormat, int format, int pixelType, byte[] pixels) {
		InternalTextureFormat internal = Arrays.stream(InternalTextureFormat.values()).filter(f -> f.getGlFormat() == internalFormat).findFirst().orElse(null);
		PixelFormat pixelFormat = Arrays.stream(PixelFormat.values()).filter(f -> f.getGlFormat() == format).findFirst().orElse(null);
		PixelType type = Arrays.stream(PixelType.values()).filter(t -> t.getGlFormat() == pixelType).findFirst().orElse(null);

		if (internal == null || pixelFormat == null || type == null) {
			VulkanUnsupported.once("raw custom textures with GL formats " + internalFormat + " / " + format + " / " + pixelType);
			return 0;
		}

		int dimension = switch (target) {
			case TEXTURE_1D -> 1;
			case TEXTURE_3D -> 3;
			default -> 2;
		};

		return VulkanTextures.createFromPixels("Iris custom texture (" + target + ")", dimension, sizeX, sizeY, sizeZ, internal, pixelFormat, type, pixels);
	}

	public TextureType getTarget() {
		return target;
	}

	public void bind(int unit) {
		IrisRenderSystem.bindTextureToUnit(target.getGlType(), unit, getGlId());
	}

	@Override
	public TextureType getType() {
		return target;
	}

	@Override
	public IntSupplier getTextureId() {
		return this::getGlId;
	}

	@Override
	public GlSampler getSampling() {
		if (shouldClamp) {
			if (shouldBlur) {
				return GlSampler.LINEAR;
			} else {
				return GlSampler.NEAREST;
			}
		} else {
			if (shouldBlur) {
				return GlSampler.LINEAR_REPEAT;
			} else {
				return GlSampler.NEAREST_REPEAT;
			}
		}
	}

	@Override
	protected void destroyInternal() {
		if (IrisVulkan.isActive()) {
			VulkanTextures.delete(getGlId());
			return;
		}

		if (getGlId() != 0) {
			GlStateManager._deleteTexture(getGlId());
		}
	}
}
