package net.irisshaders.iris.vulkan;

import com.mojang.renderpearl.backend.vulkan.Destroyable;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.irisshaders.iris.Iris;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorPoolCreateInfo;
import org.lwjgl.vulkan.VkDescriptorPoolSize;
import org.lwjgl.vulkan.VkDescriptorSetAllocateInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Storage images and storage buffers (imageLoad/imageStore, SSBOs) live in descriptor set 1, which
 * RenderPearl doesn't know about: its own bindings are push descriptors in set 0. Every image name and
 * SSBO index gets one binding for all programs (VulkanTransformer writes it into the GLSL), so set 1
 * has one layout everywhere. Sets are immutable once written and cached by their contents; a program
 * whose images change (ping-pong buffers) simply uses another cached set.
 */
public final class VulkanStorage {
	public static final int SET = 1;
	public static final int MAX_IMAGES = 32;
	public static final int BUFFER_BASE = MAX_IMAGES;
	public static final int MAX_BUFFERS = 16;
	private static final int VK_DESCRIPTOR_TYPE_STORAGE_IMAGE = 3;
	private static final int VK_DESCRIPTOR_TYPE_STORAGE_BUFFER = 7;
	private static final int VK_SHADER_STAGE_ALL = 0x7FFFFFFF;
	private static final int VK_IMAGE_LAYOUT_GENERAL = 1;
	private static final int SETS_PER_POOL = 256;

	private static final Object2IntOpenHashMap<String> IMAGE_BINDINGS = new Object2IntOpenHashMap<>();
	/** Names (CreateInfo.name) of RenderPearl pipelines whose shaders use set 1, and the compiled ones. */
	private static final Set<String> STORAGE_PIPELINE_NAMES = Collections.synchronizedSet(new HashSet<>());
	private static final Set<Object> STORAGE_PIPELINES = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
	private static final Map<List<Write>, Long> SETS = new HashMap<>();
	private static final List<Long> POOLS = new ArrayList<>();
	private static final Write[] BUFFERS = new Write[MAX_BUFFERS];
	private static long setLayout;
	private static int setsInPool = SETS_PER_POOL;
	/** The set of the program bound last in a render pass, bound for its draws (see MixinVulkanRenderPass_Storage). */
	private static long graphicsSet;

	static {
		IMAGE_BINDINGS.defaultReturnValue(-1);
	}

	private VulkanStorage() {
	}

	/** One descriptor: an image view, or a buffer range. */
	public record Write(int binding, long imageView, long buffer, long size) {
		public static Write image(int binding, long imageView) {
			return new Write(binding, imageView, 0L, 0L);
		}

		public static Write buffer(int binding, long buffer, long size) {
			return new Write(binding, 0L, buffer, size);
		}
	}

	/** The binding of a storage image name, assigned on first use; -1 once all are taken. */
	public static synchronized int imageBinding(String name) {
		int binding = IMAGE_BINDINGS.getInt(name);

		if (binding < 0 && IMAGE_BINDINGS.size() < MAX_IMAGES) {
			binding = IMAGE_BINDINGS.size();
			IMAGE_BINDINGS.put(name, binding);
		}

		return binding;
	}

	/** The binding of an existing name, or -1. */
	public static synchronized int existingImageBinding(String name) {
		return IMAGE_BINDINGS.getInt(name);
	}

	/** The binding of SSBO {@code index} (its GL binding point), or -1 past the last one. */
	public static int bufferBinding(int index) {
		return index >= 0 && index < MAX_BUFFERS ? BUFFER_BASE + index : -1;
	}

	/** Called before RenderPearl compiles a pipeline of this name whose GLSL uses set 1. */
	public static void markPipeline(String name) {
		STORAGE_PIPELINE_NAMES.add(name);
	}

	public static boolean usesStorage(String pipelineName) {
		return STORAGE_PIPELINE_NAMES.contains(pipelineName);
	}

	/** The backend pipeline RenderPearl compiled for a marked name. */
	public static void markCompiled(Object pipeline) {
		STORAGE_PIPELINES.add(pipeline);
	}

	public static synchronized long setLayout() {
		if (setLayout == 0L) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(MAX_IMAGES + MAX_BUFFERS, stack);

				for (int i = 0; i < MAX_IMAGES + MAX_BUFFERS; i++) {
					bindings.get(i)
						.binding(i)
						.descriptorType(i < BUFFER_BASE ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE : VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
						.descriptorCount(1)
						.stageFlags(VK_SHADER_STAGE_ALL);
				}

				VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(bindings);
				LongBuffer layout = stack.callocLong(1);
				check(VK10.vkCreateDescriptorSetLayout(VulkanRaw.vkDevice(), info, null, layout), "storage descriptor set layout");
				setLayout = layout.get(0);
			}
		}

		return setLayout;
	}

	/** A set with these descriptors written (others stay unwritten; programs only use what they declare). */
	public static long descriptorSet(List<Write> writes) {
		Long cached = SETS.get(writes);

		if (cached != null) {
			return cached;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			if (setsInPool >= SETS_PER_POOL) {
				VkDescriptorPoolSize.Buffer sizes = VkDescriptorPoolSize.calloc(2, stack);
				sizes.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(MAX_IMAGES * SETS_PER_POOL);
				sizes.get(1).type(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(MAX_BUFFERS * SETS_PER_POOL);
				LongBuffer pool = stack.callocLong(1);
				check(VK10.vkCreateDescriptorPool(VulkanRaw.vkDevice(),
					VkDescriptorPoolCreateInfo.calloc(stack).sType$Default().maxSets(SETS_PER_POOL).pPoolSizes(sizes), null, pool), "storage descriptor pool");
				POOLS.add(pool.get(0));
				setsInPool = 0;
			}

			LongBuffer set = stack.callocLong(1);
			check(VK10.vkAllocateDescriptorSets(VulkanRaw.vkDevice(), VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
				.descriptorPool(POOLS.getLast()).pSetLayouts(stack.longs(setLayout())), set), "storage descriptor set");
			setsInPool++;

			if (!writes.isEmpty()) {
				VkWriteDescriptorSet.Buffer descriptors = VkWriteDescriptorSet.calloc(writes.size(), stack);

				for (int i = 0; i < writes.size(); i++) {
					Write write = writes.get(i);
					VkWriteDescriptorSet descriptor = descriptors.get(i).sType$Default().dstSet(set.get(0)).dstBinding(write.binding()).descriptorCount(1);

					if (write.buffer() != 0L) {
						descriptor.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER)
							.pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(write.buffer()).offset(0).range(write.size()));
					} else {
						descriptor.descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
							.pImageInfo(VkDescriptorImageInfo.calloc(1, stack).imageView(write.imageView()).imageLayout(VK_IMAGE_LAYOUT_GENERAL));
					}
				}

				VK10.vkUpdateDescriptorSets(VulkanRaw.vkDevice(), descriptors, null);
			}

			SETS.put(List.copyOf(writes), set.get(0));
			return set.get(0);
		}
	}

	/** glBindBufferBase(GL_SHADER_STORAGE_BUFFER, index, ...): the buffer of SSBO {@code index}, or none (buffer 0). */
	public static void setBuffer(int index, long buffer, long size) {
		int binding = bufferBinding(index);

		if (binding >= 0) {
			BUFFERS[index] = buffer == 0L ? null : Write.buffer(binding, buffer, size);
		}
	}

	@Nullable
	public static Write buffer(int binding) {
		return BUFFERS[binding - BUFFER_BASE];
	}

	public static void setGraphicsSet(long set) {
		graphicsSet = set;
	}

	/** After RenderPearl pushed set 0 for a draw: set 1 for pipelines that use it. */
	public static void bindGraphics(VkCommandBuffer commandBuffer, Object pipeline, long pipelineLayout) {
		if (graphicsSet != 0L && STORAGE_PIPELINES.contains(pipeline)) {
			try (MemoryStack stack = MemoryStack.stackPush()) {
				VK10.vkCmdBindDescriptorSets(commandBuffer, VK10.VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, SET, stack.longs(graphicsSet), null);
			}
		}
	}

	/**
	 * A new shader pack: the cached sets may point at destroyed images. The pools go once the frames
	 * still using them are done (RenderPearl's destroy queue).
	 */
	public static void clear() {
		SETS.clear();
		graphicsSet = 0L;
		setsInPool = SETS_PER_POOL;

		if (!POOLS.isEmpty()) {
			List<Long> pools = List.copyOf(POOLS);
			POOLS.clear();
			VulkanRaw.encoder().queueForDestroy((Destroyable) () -> pools.forEach(pool -> VK10.vkDestroyDescriptorPool(VulkanRaw.vkDevice(), pool, null)));
		}
	}

	private static void check(int result, String what) {
		if (result != VK10.VK_SUCCESS) {
			Iris.logger.error("Vulkan: creating {} failed ({})", what, result);
			throw new IllegalStateException("Vulkan: creating " + what + " failed (" + result + ")");
		}
	}
}
