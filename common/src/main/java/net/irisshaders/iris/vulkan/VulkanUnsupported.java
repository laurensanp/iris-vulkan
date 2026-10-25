package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.IrisRenderSystem;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Set;

/** Pack features the Vulkan port does not run yet: logged once each instead of failing the pack. */
public final class VulkanUnsupported {
	private static final Set<String> REPORTED = ConcurrentHashMap.newKeySet();

	private VulkanUnsupported() {
	}

	/** True (and logged) if this compute program can't be built here; callers leave it out like an empty source. */
	public static boolean computeSkipped(String program) {
		if (IrisRenderSystem.supportsCompute()) {
			return false;
		}

		once("compute shaders (" + program + ")");
		return true;
	}

	public static void once(String feature) {
		if (REPORTED.add(feature)) {
			Iris.logger.warn("Not supported on Vulkan yet, skipped: {}", feature);
		}
	}
}
