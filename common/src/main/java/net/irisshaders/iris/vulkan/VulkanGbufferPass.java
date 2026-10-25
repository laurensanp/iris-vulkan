package net.irisshaders.iris.vulkan;

import org.jetbrains.annotations.Nullable;

/** Added to RenderPearl's render passes: which gbuffers the pass writes, if it was redirected (see {@link VulkanGbuffers}). */
public interface VulkanGbufferPass {
	void iris$setGbufferTarget(@Nullable VulkanGbuffers.Target target);
}
