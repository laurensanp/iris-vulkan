package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
import net.irisshaders.iris.gl.texture.InternalTextureFormat;
import net.irisshaders.iris.gl.texture.PixelFormat;
import net.irisshaders.iris.gl.texture.PixelType;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import org.joml.Vector4f;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Iris passes textures around as GL names (ints, often behind an IntSupplier). On Vulkan those ints
 * are keys into this table: Iris' own render targets get one when created, game textures (atlas,
 * lightmap, depth) the first time Iris asks for them. Id 0 stays "no texture", like in GL.
 */
public final class VulkanTextures {
	private static final Int2ObjectOpenHashMap<Entry> BY_ID = new Int2ObjectOpenHashMap<>();
	private static final Map<GpuTexture, Integer> BY_TEXTURE = new IdentityHashMap<>();
	/** Textures standing in for a GL format without alpha (RGB16F as RGBA16F, ...): alpha stays 1, see {@link #keepOpaqueAlpha}. */
	private static final Set<GpuTexture> OPAQUE_ALPHA = Collections.newSetFromMap(new IdentityHashMap<>());
	private static final int SWEEP_INTERVAL = 256;
	private static int nextId = 1;
	private static int registrationsSinceSweep;

	private VulkanTextures() {
	}

	/**
	 * A texture Iris owns: destroyed again through {@link #delete(int)}. Render targets are storage images
	 * too where the format allows it, for packs that write colorimgN / shadowcolorimgN.
	 */
	public static int create(String label, int usage, GpuFormat format, int width, int height, int mipLevels) {
		RenderSystem.assertOnRenderThread();
		GpuTexture texture = VulkanRaw.createTexture(label, usage, format, 2, width, height, 1, mipLevels, true);
		return register(texture, true);
	}

	/** A pack's custom image (1D / 2D / 3D storage image, sampled too), cleared to zero. */
	public static int createImage(String label, GpuFormat format, int dimension, int width, int height, int depth) {
		RenderSystem.assertOnRenderThread();
		int id = register(VulkanRaw.createTexture(label, IMAGE_USAGE, format, dimension, width, height, depth, 1, true), true);
		clear(id);
		return id;
	}

	/**
	 * A texture from a pack's raw texture file (glTexImage1D/2D/3D with GL pixel data): the pixels are
	 * turned into the Vulkan format's layout (three channels widened to four, BGR swapped, float to half
	 * where needed) and copied in. Returns 0 for layouts that aren't handled.
	 */
	public static int createFromPixels(String label, int dimension, int width, int height, int depth, InternalTextureFormat internalFormat,
									   PixelFormat pixelFormat, PixelType pixelType, byte[] pixels) {
		GpuFormat format = VulkanFormats.toRenderTarget(internalFormat);
		ByteBuffer data = convertPixels(format, pixelFormat, pixelType, pixels, width * Math.max(1, height) * Math.max(1, depth));

		if (data == null) {
			VulkanUnsupported.once("raw texture " + label + " with " + pixelFormat + " / " + pixelType + " data for " + internalFormat);
			return 0;
		}

		GpuTexture texture = VulkanRaw.createTexture(label, IMAGE_USAGE, format, dimension, width, height, depth, 1, false);
		int id = register(texture, true);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			GpuBufferSlice staging = RenderSystem.getDevice().createCommandEncoder().transientMemory().uploadStaging(data, 16, GpuBuffer.USAGE_COPY_SRC);
			VkCommandBuffer commandBuffer = VulkanRaw.commandBuffer();
			VkBufferImageCopy.Buffer region = VkBufferImageCopy.calloc(1, stack).bufferOffset(staging.offset());
			region.imageSubresource().aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).mipLevel(0).baseArrayLayer(0).layerCount(1);
			region.imageExtent().set(width, dimension == 1 ? 1 : height, dimension == 3 ? depth : 1);
			VulkanRaw.barrier(commandBuffer);
			VK10.vkCmdCopyBufferToImage(commandBuffer, ((VulkanGpuBuffer) staging.buffer()).vkBuffer(), ((VulkanGpuTexture) texture).vkImage(),
				VK10.VK_IMAGE_LAYOUT_GENERAL, region);
			VulkanRaw.barrier(commandBuffer);
		} finally {
			MemoryUtil.memFree(data);
		}

		return id;
	}

	/** {@code pixels} in GL's layout as tightly packed texels of {@code format}, or null if not handled. */
	@Nullable
	private static ByteBuffer convertPixels(GpuFormat format, PixelFormat pixelFormat, PixelType pixelType, byte[] pixels, int texels) {
		String name = format.name();
		int dstChannels = format.componentCount();
		int dstSize = name.contains("32") ? 4 : name.contains("16") ? 2 : name.contains("8") ? 1 : -1;
		int srcChannels = pixelFormat.getComponentCount();
		int srcSize = pixelType.getByteSize();
		boolean packed = pixelType.name().contains("_REV") || pixelType.name().matches(".*_\\d+_\\d+.*");
		boolean halfFromFloat = srcSize == 4 && dstSize == 2 && pixelType == PixelType.FLOAT && name.endsWith("_FLOAT");

		if (dstSize < 0 || packed || (srcSize != dstSize && !halfFromFloat) || pixels.length < texels * srcChannels * srcSize) {
			return null;
		}

		ByteBuffer in = ByteBuffer.wrap(pixels).order(ByteOrder.nativeOrder());
		ByteBuffer out = MemoryUtil.memAlloc(texels * dstChannels * dstSize);
		boolean bgr = pixelFormat.name().startsWith("BGR");

		for (int texel = 0; texel < texels; texel++) {
			for (int channel = 0; channel < dstChannels; channel++) {
				int source = bgr && channel < 3 ? 2 - channel : channel;

				if (source < srcChannels) {
					int offset = (texel * srcChannels + source) * srcSize;

					if (halfFromFloat) {
						out.putShort(Float.floatToFloat16(in.getFloat(offset)));
					} else {
						for (int b = 0; b < dstSize; b++) {
							out.put(pixels[offset + b]);
						}
					}
				} else {
					putComponent(out, name, dstSize, channel == 3);
				}
			}
		}

		return out.flip();
	}

	/** A missing channel: 0, or "one" for alpha (GL reads absent alpha as 1). */
	private static void putComponent(ByteBuffer out, String format, int size, boolean one) {
		long value = 0;

		if (one) {
			if (format.endsWith("_FLOAT")) {
				value = size == 4 ? Float.floatToRawIntBits(1.0f) : Float.floatToFloat16(1.0f);
			} else if (format.endsWith("_UNORM")) {
				value = (1L << (size * 8)) - 1;
			} else if (format.endsWith("_SNORM")) {
				value = (1L << (size * 8 - 1)) - 1;
			} else {
				value = 1;
			}
		}

		switch (size) {
			case 1 -> out.put((byte) value);
			case 2 -> out.putShort((short) value);
			default -> out.putInt((int) value);
		}
	}

	/** A new size for a custom image, keeping its id. */
	public static void replaceImage(int id, String label, GpuFormat format, int dimension, int width, int height, int depth) {
		replaceWith(id, VulkanRaw.createTexture(label, IMAGE_USAGE, format, dimension, width, height, depth, 1, true));
		clear(id);
	}

	/**
	 * Every mip level to zero (glClearTexImage). Recorded into the frame's command buffer between
	 * RenderPearl's passes; also works for 3D and integer images, unlike RenderPearl's clear.
	 */
	public static void clear(int id) {
		GpuTexture texture = texture(id);

		if (VulkanRaw.debug("noclear")) {
			return;
		}

		if (!(texture instanceof VulkanGpuTexture vulkan)) {
			return;
		}

		VkCommandBuffer commandBuffer = VulkanRaw.commandBuffer();

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VulkanRaw.checkpoint(commandBuffer, true, "Iris clear " + texture.getLabel());
			VulkanRaw.barrier(commandBuffer);
			VkImageSubresourceRange range = VkImageSubresourceRange.calloc(stack)
				.aspectMask(VK10.VK_IMAGE_ASPECT_COLOR_BIT).baseMipLevel(0).levelCount(texture.getMipLevels()).baseArrayLayer(0)
				.layerCount(VulkanRaw.dimension(texture) == 3 ? 1 : texture.getDepthOrLayers());
			VK10.vkCmdClearColorImage(commandBuffer, vulkan.vkImage(), VK10.VK_IMAGE_LAYOUT_GENERAL, VkClearColorValue.calloc(stack), range);
			VulkanRaw.barrier(commandBuffer);
			VulkanRaw.checkpoint(commandBuffer, false, "Iris clear " + texture.getLabel());
		}
	}

	private static final int IMAGE_USAGE = GpuTexture.USAGE_TEXTURE_BINDING | GpuTexture.USAGE_COPY_DST | GpuTexture.USAGE_COPY_SRC;

	/**
	 * GL formats without alpha (RGB16F, RGB8, ...) have an RGBA texture on Vulkan, but GL reads their alpha as 1
	 * and packs rely on it (Solas drops water reflections where gaux1.a is 0). Such a texture starts with alpha 1
	 * in every mip level, and passes don't write its alpha ({@link #writeMask}), so it reads like on GL.
	 */
	public static void keepOpaqueAlpha(int id) {
		GpuTexture texture = texture(id);

		if (texture != null) {
			OPAQUE_ALPHA.add(texture);
			RenderSystem.getDevice().createCommandEncoder().clearColorTexture(texture, new Vector4f(0.0f, 0.0f, 0.0f, 1.0f));
		}
	}

	public static boolean hasOpaqueAlpha(GpuTexture texture) {
		return OPAQUE_ALPHA.contains(texture);
	}

	/** The write mask to render into {@code texture} with: without alpha for {@link #keepOpaqueAlpha} textures. */
	public static int writeMask(GpuTexture texture, int writeMask) {
		return OPAQUE_ALPHA.contains(texture) ? writeMask & ~ColorTargetState.WRITE_ALPHA : writeMask;
	}

	/** The id of a texture someone else owns (the game, Sodium); stable for the texture's lifetime. */
	public static int idOf(GpuTexture texture) {
		Integer id = BY_TEXTURE.get(texture);
		return id != null ? id : register(texture, false);
	}

	/** Gives an owned id a new texture (resize), keeping the id the pack's programs already sampled. */
	public static void replace(int id, String label, int usage, GpuFormat format, int width, int height, int mipLevels) {
		replaceWith(id, VulkanRaw.createTexture(label, usage, format, 2, width, height, 1, mipLevels, true));
	}

	private static void replaceWith(int id, GpuTexture texture) {
		Entry old = BY_ID.get(id);

		if (old == null || !old.owned) {
			throw new IllegalStateException("No Iris-owned Vulkan texture with id " + id);
		}

		BY_TEXTURE.remove(old.texture);
		OPAQUE_ALPHA.remove(old.texture);
		old.close();
		VulkanStorage.clear();

		BY_ID.put(id, new Entry(texture, true));
		BY_TEXTURE.put(texture, id);
	}

	public static void delete(int id) {
		Entry entry = BY_ID.remove(id);

		if (entry != null) {
			BY_TEXTURE.remove(entry.texture);
			OPAQUE_ALPHA.remove(entry.texture);
			entry.close();
			VulkanStorage.clear();
		}
	}

	@Nullable
	public static GpuTexture texture(int id) {
		Entry entry = BY_ID.get(id);
		return entry != null && !entry.texture.isClosed() ? entry.texture : null;
	}

	/** A view over all mip levels, created on first use. */
	@Nullable
	public static GpuTextureView view(int id) {
		Entry entry = BY_ID.get(id);

		if (entry == null || entry.texture.isClosed()) {
			return null;
		}

		if (entry.view == null) {
			entry.view = RenderSystem.getDevice().createTextureView(entry.texture);
		}

		return entry.view;
	}

	/** A view of one mip level (what a render pass writes into), created on first use. */
	@Nullable
	public static GpuTextureView levelView(int id, int level) {
		Entry entry = BY_ID.get(id);

		if (entry == null || entry.texture.isClosed()) {
			return null;
		}

		if (entry.texture.getMipLevels() == 1) {
			return view(id);
		}

		if (entry.levelViews == null) {
			entry.levelViews = new GpuTextureView[entry.texture.getMipLevels()];
		}

		if (entry.levelViews[level] == null) {
			entry.levelViews[level] = RenderSystem.getDevice().createTextureView(entry.texture, level, 1);
		}

		return entry.levelViews[level];
	}

	private static int register(GpuTexture texture, boolean owned) {
		if (++registrationsSinceSweep >= SWEEP_INTERVAL) {
			registrationsSinceSweep = 0;
			sweepClosed();
		}

		int id = nextId++;
		BY_ID.put(id, new Entry(texture, owned));
		BY_TEXTURE.put(texture, id);
		return id;
	}

	/** Game textures are closed by their owners without telling us; forget those. */
	private static void sweepClosed() {
		BY_ID.int2ObjectEntrySet().removeIf(e -> {
			Entry entry = e.getValue();

			if (!entry.owned && entry.texture.isClosed()) {
				BY_TEXTURE.remove(entry.texture);

				entry.closeViews();
				return true;
			}

			return false;
		});
	}

	private static final class Entry {
		final GpuTexture texture;
		final boolean owned;
		@Nullable
		GpuTextureView view;
		@Nullable
		GpuTextureView[] levelViews;

		Entry(GpuTexture texture, boolean owned) {
			this.texture = texture;
			this.owned = owned;
		}

		void closeViews() {
			if (view != null) {
				view.close();
			}

			if (levelViews != null) {
				for (GpuTextureView levelView : levelViews) {
					if (levelView != null) {
						levelView.close();
					}
				}
			}
		}

		void close() {
			closeViews();

			if (owned) {
				texture.close();
			}
		}
	}
}
