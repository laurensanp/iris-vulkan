package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.Iris;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Called at the top of every GlStateManager method when the game was started for Vulkan (see
 * VulkanGlGuardTransformer). Before the device exists it never suppresses, so the GL backend can
 * still start if Vulkan fails and the game falls back to OpenGL.
 */
public final class VulkanGlGuard {
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private VulkanGlGuard() {
	}

	public static boolean suppress(String method) {
		if (RenderSystem.tryGetDevice() == null || !IrisVulkan.isActive()) {
			return false;
		}

		if (REPORTED.add(method)) {
			Iris.logger.info("Vulkan: ignored GlStateManager." + method + " (no GL context)");
		}

		return true;
	}
}
