package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.mixin.vulkan.VulkanCommandEncoderAccessor;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkFormatProperties;

import java.util.EnumMap;
import java.util.Map;

/**
 * The Vulkan objects behind RenderPearl, for what its API has no words for (compute, storage images and
 * buffers, 3D images, geometry shaders). RenderPearl keeps every image in VK_IMAGE_LAYOUT_GENERAL and
 * puts a full memory barrier after each render pass and copy, so work recorded here between its passes
 * needs no layout transitions, only the same kind of barrier around it.
 */
public final class VulkanRaw {
	public static final int VK_IMAGE_USAGE_STORAGE_BIT = 0x8;
	private static final int VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT = 0x2;
	private static final Map<GpuFormat, Boolean> STORAGE_FORMATS = new EnumMap<>(GpuFormat.class);

	/** Device features the port turned on at device creation (see MixinVulkanBackend_Features). */
	public static boolean geometryShader;
	public static boolean tessellationShader;
	public static boolean vertexStores;
	public static boolean fragmentStores;

	/** Extra VkImageUsageFlags, image dimension (1, 2, 3) and 3D depth for the next texture RenderPearl creates. */
	private static int nextExtraUsage;
	private static int nextDimension = 2;
	private static int nextDepth = 1;

	private VulkanRaw() {
	}

	/**
	 * A RenderPearl texture that may also be a 1D / 3D image ({@code depth} slices) and a storage image.
	 * It is an ordinary GpuTexture otherwise: sampled through RenderPearl, closed like any other.
	 */
	public static GpuTexture createTexture(String label, int usage, GpuFormat format, int dimension, int width, int height, int depth,
										   int mipLevels, boolean storage) {
		nextExtraUsage = storage && supportsStorage(format) ? VK_IMAGE_USAGE_STORAGE_BIT : 0;
		nextDimension = dimension;
		nextDepth = Math.max(1, depth);

		try {
			return RenderSystem.getDevice().createTexture(label, usage, format, width, dimension == 1 ? 1 : height,
				dimension == 3 ? nextDepth : 1, mipLevels);
		} finally {
			nextExtraUsage = 0;
			nextDimension = 2;
			nextDepth = 1;
		}
	}

	public static int nextExtraUsage() {
		return nextExtraUsage;
	}

	public static int nextDimension() {
		return nextDimension;
	}

	public static int nextDepth() {
		return nextDepth;
	}

	/** 1, 2 or 3; 2 for textures RenderPearl made on its own. */
	public static int dimension(GpuTexture texture) {
		return texture instanceof VulkanTextureDimension dimension ? dimension.iris$dimension() : 2;
	}

	public static VulkanDevice device() {
		return (VulkanDevice) ((GpuDeviceAccessor) RenderSystem.getDevice()).getBackend();
	}

	public static VkDevice vkDevice() {
		return device().vkDevice();
	}

	public static long vma() {
		return device().vma();
	}

	public static VulkanCommandEncoder encoder() {
		return device().createCommandEncoder();
	}

	/** The game's command buffer for this frame; only valid outside RenderPearl's render passes. */
	public static VkCommandBuffer commandBuffer() {
		VulkanCommandEncoderAccessor encoder = (VulkanCommandEncoderAccessor) encoder();

		if (encoder.iris$currentRenderPass() != null) {
			throw new IllegalStateException("Raw Vulkan commands recorded inside a RenderPearl render pass");
		}

		return encoder.iris$commandBuffer();
	}

	/** RenderPearl's full barrier (all commands, all memory), the same it records after its own passes. */
	public static void barrier(VkCommandBuffer commandBuffer) {
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanCommandEncoder.memoryBarrier(commandBuffer, stack);
		}
	}

	/** Whether images of this format can be storage images (imageLoad / imageStore). */
	public static boolean supportsStorage(GpuFormat format) {
		return STORAGE_FORMATS.computeIfAbsent(format, f -> {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkFormatProperties properties = VkFormatProperties.calloc(stack);
				VK10.vkGetPhysicalDeviceFormatProperties(vkDevice().getPhysicalDevice(), VulkanConst.toVk(f), properties);
				return (properties.optimalTilingFeatures() & VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT) != 0;
			}
		});
	}
}
