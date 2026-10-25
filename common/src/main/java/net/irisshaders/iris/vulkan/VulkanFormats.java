package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;

/**
 * Iris' GL internal formats as RenderPearl formats for render targets. Vulkan drivers do not render
 * to three-channel or packed legacy formats, so those become the closest renderable four-channel
 * format with at least the precision and range the pack asked for.
 */
public final class VulkanFormats {
	private VulkanFormats() {
	}

	public static GpuFormat toRenderTarget(InternalTextureFormat format) {
		return switch (format) {
			case RGBA, RGBA8, RGB8, RGBA2, RGBA4, R3_G3_B2, RGB5_A1, RGB565 -> GpuFormat.RGBA8_UNORM;
			case R8 -> GpuFormat.R8_UNORM;
			case RG8 -> GpuFormat.RG8_UNORM;
			case R8_SNORM -> GpuFormat.R8_SNORM;
			case RG8_SNORM -> GpuFormat.RG8_SNORM;
			case RGB8_SNORM, RGBA8_SNORM -> GpuFormat.RGBA8_SNORM;
			case R16 -> GpuFormat.R16_UNORM;
			case RG16 -> GpuFormat.RG16_UNORM;
			case RGB16, RGBA16 -> GpuFormat.RGBA16_UNORM;
			case R16_SNORM -> GpuFormat.R16_SNORM;
			case RG16_SNORM -> GpuFormat.RG16_SNORM;
			case RGB16_SNORM, RGBA16_SNORM -> GpuFormat.RGBA16_SNORM;
			case R16F -> GpuFormat.R16_FLOAT;
			case RG16F -> GpuFormat.RG16_FLOAT;
			case RGB16F, RGBA16F, RGB9_E5 -> GpuFormat.RGBA16_FLOAT;
			case R32F -> GpuFormat.R32_FLOAT;
			case RG32F -> GpuFormat.RG32_FLOAT;
			case RGB32F, RGBA32F -> GpuFormat.RGBA32_FLOAT;
			case R8I -> GpuFormat.R8_SINT;
			case RG8I -> GpuFormat.RG8_SINT;
			case RGB8I, RGBA8I -> GpuFormat.RGBA8_SINT;
			case R8UI -> GpuFormat.R8_UINT;
			case RG8UI -> GpuFormat.RG8_UINT;
			case RGB8UI, RGBA8UI -> GpuFormat.RGBA8_UINT;
			case R16I -> GpuFormat.R16_SINT;
			case RG16I -> GpuFormat.RG16_SINT;
			case RGB16I, RGBA16I -> GpuFormat.RGBA16_SINT;
			case R16UI -> GpuFormat.R16_UINT;
			case RG16UI -> GpuFormat.RG16_UINT;
			case RGB16UI, RGBA16UI -> GpuFormat.RGBA16_UINT;
			case R32I -> GpuFormat.R32_SINT;
			case RG32I -> GpuFormat.RG32_SINT;
			case RGB32I, RGBA32I -> GpuFormat.RGBA32_SINT;
			case R32UI -> GpuFormat.R32_UINT;
			case RG32UI -> GpuFormat.RG32_UINT;
			case RGB32UI, RGBA32UI -> GpuFormat.RGBA32_UINT;
			case RGB10_A2 -> GpuFormat.RGB10A2_UNORM;
			case RGB10_A2UI -> GpuFormat.RGB10A2_UINT;
			case R11F_G11F_B10F -> GpuFormat.RG11B10_FLOAT;
		};
	}
}
