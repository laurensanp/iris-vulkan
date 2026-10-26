package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.backend.vulkan.Destroyable;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.vma.Vma;
import org.lwjgl.util.vma.VmaAllocationCreateInfo;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkBufferCopy;
import org.lwjgl.vulkan.VkBufferCreateInfo;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

/**
 * Shader storage buffers (SSBOs) on Vulkan: GL buffer names are keys into this table, like texture
 * names in VulkanTextures. The buffers are raw VMA buffers (RenderPearl has no storage buffer usage),
 * also usable for indirect dispatch, and start out zeroed or with the pack's initial content.
 */
public final class VulkanBuffers {
	private static final int VK_BUFFER_USAGE = VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT
		| VK10.VK_BUFFER_USAGE_TRANSFER_DST_BIT | VK10.VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
	private static final Int2ObjectOpenHashMap<Buffer> BUFFERS = new Int2ObjectOpenHashMap<>();
	private static int nextId = 1;

	private VulkanBuffers() {
	}

	private record Buffer(long vkBuffer, long allocation, long size) {
	}

	/** glCreateBuffers: a name without storage yet. */
	public static int reserve() {
		return nextId++;
	}

	/** glBufferStorage (+ glBufferSubData / glClearBufferSubData): new storage for this name. */
	public static void allocate(int id, long size, @Nullable ByteBuffer content) {
		delete(id);
		size = Math.max(4, size);

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkBufferCreateInfo info = VkBufferCreateInfo.calloc(stack).sType$Default().size(size).usage(VK_BUFFER_USAGE)
				.sharingMode(VK10.VK_SHARING_MODE_EXCLUSIVE);
			VmaAllocationCreateInfo allocationInfo = VmaAllocationCreateInfo.calloc(stack).usage(Vma.VMA_MEMORY_USAGE_GPU_ONLY);
			LongBuffer buffer = stack.callocLong(1);
			PointerBuffer allocation = stack.callocPointer(1);
			int result = Vma.vmaCreateBuffer(VulkanRaw.vma(), info, allocationInfo, buffer, allocation, null);

			if (result != VK10.VK_SUCCESS) {
				throw new IllegalStateException("Vulkan: couldn't create a shader storage buffer of " + size + " bytes (" + result + ")");
			}

			BUFFERS.put(id, new Buffer(buffer.get(0), allocation.get(0), size));
			VkCommandBuffer commandBuffer = VulkanRaw.commandBuffer();
			VulkanRaw.barrier(commandBuffer);

			if (content != null && content.remaining() > 0) {
				GpuBufferSlice staging = RenderSystem.getDevice().createCommandEncoder().transientMemory()
					.uploadStaging(content.duplicate(), 4, GpuBuffer.USAGE_COPY_SRC);
				VkBufferCopy.Buffer region = VkBufferCopy.calloc(1, stack).srcOffset(staging.offset()).dstOffset(0)
					.size(Math.min(size, staging.length()));
				VK10.vkCmdCopyBuffer(commandBuffer, ((VulkanGpuBuffer) staging.buffer()).vkBuffer(), buffer.get(0), region);
			} else {
				VK10.vkCmdFillBuffer(commandBuffer, buffer.get(0), 0, VK10.VK_WHOLE_SIZE, 0);
			}

			VulkanRaw.barrier(commandBuffer);
		}

		VulkanStorage.clear();
	}

	/** The VkBuffer of a name, or 0. */
	public static long vkBuffer(int id) {
		Buffer buffer = BUFFERS.get(id);
		return buffer == null ? 0L : buffer.vkBuffer();
	}

	public static long size(int id) {
		Buffer buffer = BUFFERS.get(id);
		return buffer == null ? 0L : buffer.size();
	}

	/** glDeleteBuffers; the memory goes once the frames using it are done. */
	public static void delete(int id) {
		Buffer buffer = BUFFERS.remove(id);

		if (buffer != null) {
			VulkanStorage.clear();
			VulkanRaw.encoder().queueForDestroy((Destroyable) () -> Vma.vmaDestroyBuffer(VulkanRaw.vma(), buffer.vkBuffer(), buffer.allocation()));
		}
	}
}
