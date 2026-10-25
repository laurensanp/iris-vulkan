package net.irisshaders.iris.vulkan;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.device.GpuDevice;
import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
import net.irisshaders.iris.mixin.GpuDeviceAccessor;
import net.irisshaders.iris.mixin.IrisMixinPlugin;

/**
 * Entry point of the Vulkan port: tells the rest of Iris which backend the game device runs on.
 * Code that still talks to OpenGL directly checks {@link #isActive()} first.
 */
public final class IrisVulkan {
	private static Boolean active;

	private IrisVulkan() {
	}

	/** True when the game's GpuDevice is RenderPearl's Vulkan backend. */
	public static boolean isActive() {
		if (active == null) {
			GpuDevice device = RenderSystem.tryGetDevice();

			if (device == null) {
				// Before the device exists only the mixin plugin's guess is known; do not cache it.
				return IrisMixinPlugin.usingVulkan;
			}

			active = ((GpuDeviceAccessor) device).getBackend() instanceof VulkanDevice;
		}

		return active;
	}
}
