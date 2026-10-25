package net.irisshaders.iris.targets;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanTextures;
import net.minecraft.client.Minecraft;
import org.joml.Vector4f;

import java.util.Objects;
import java.util.function.IntSupplier;

public class ClearPass {
	private final Vector4f color;
	private final IntSupplier viewportX;
	private final IntSupplier viewportY;
	private final GlFramebuffer framebuffer;
	private final int clearFlags;

	public ClearPass(Vector4f color, IntSupplier viewportX, IntSupplier viewportY, GlFramebuffer framebuffer, int clearFlags) {
		this.color = color;
		this.viewportX = viewportX;
		this.viewportY = viewportY;
		this.framebuffer = framebuffer;
		this.clearFlags = clearFlags;
	}

	public void execute(Vector4f defaultClearColor) {
		if (IrisVulkan.isActive()) {
			// Clear passes only clear colour buffers (depth is the game's own); clear each attachment whole.
			Vector4f clearColor = this.color != null ? this.color : Objects.requireNonNull(defaultClearColor);

			for (GpuTextureView view : framebuffer.vulkanColorViews()) {
				// A format without alpha keeps alpha 1 (see VulkanTextures.keepOpaqueAlpha).
				Vector4f color = VulkanTextures.hasOpaqueAlpha(view.texture()) ? new Vector4f(clearColor.x, clearColor.y, clearColor.z, 1.0f) : clearColor;
				RenderSystem.getDevice().createCommandEncoder().clearColorTexture(view.texture(), color);
			}

			return;
		}

		GlStateManager._viewport(0, 0, viewportX.getAsInt(), viewportY.getAsInt());
		framebuffer.bind();

		Vector4f color = Objects.requireNonNull(defaultClearColor);

		if (this.color != null) {
			color = this.color;
		}

		IrisRenderSystem.clearColor(color.x, color.y, color.z, color.w);
		GlStateManager._disableScissorTest();
		GlStateManager._clear(clearFlags);
	}

	public GlFramebuffer getFramebuffer() {
		return framebuffer;
	}
}
