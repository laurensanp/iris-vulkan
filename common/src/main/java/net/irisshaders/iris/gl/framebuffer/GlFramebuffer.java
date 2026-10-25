package net.irisshaders.iris.gl.framebuffer;

import com.mojang.renderpearl.backend.opengl.GlStateManager;
import com.mojang.renderpearl.backend.opengl.GlTexture;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import it.unimi.dsi.fastutil.ints.Int2IntArrayMap;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import net.irisshaders.iris.gl.GlResource;
import net.irisshaders.iris.gl.IrisRenderSystem;
import net.irisshaders.iris.gl.texture.DepthBufferFormat;
import net.irisshaders.iris.pbr.TextureInfoCache;
import net.irisshaders.iris.vulkan.IrisVulkan;
import net.irisshaders.iris.vulkan.VulkanTextures;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL30C;

import java.util.ArrayList;
import java.util.List;

public class GlFramebuffer extends GlResource {
	// On Vulkan a framebuffer is only this bookkeeping; render passes are opened from it per draw.
	private static int nextVulkanId = 1;

	private final Int2IntMap attachments;
	private final int maxDrawBuffers;
	private final int maxColorAttachments;
	private boolean hasDepthAttachment;
	private int depthTexture;
	private int[] vulkanDrawBuffers = new int[0];

	public GlFramebuffer() {
		super(IrisVulkan.isActive() ? nextVulkanId++ : IrisRenderSystem.createFramebuffer());

		this.attachments = new Int2IntArrayMap();
		if (IrisVulkan.isActive()) {
			this.maxDrawBuffers = 8;
			this.maxColorAttachments = 8;
		} else {
			this.maxDrawBuffers = GlStateManager._getInteger(GL30C.GL_MAX_DRAW_BUFFERS);
			this.maxColorAttachments = GlStateManager._getInteger(GL30C.GL_MAX_COLOR_ATTACHMENTS);
		}
		this.hasDepthAttachment = false;
	}

	public void addDepthAttachment(GpuTexture texture) {
		if (IrisVulkan.isActive()) {
			this.depthTexture = VulkanTextures.idOf(texture);
			this.hasDepthAttachment = true;
			return;
		}

		int fb = getGlId();

		// TODO: NeoForge 1.21.5
		//if (texture.getFormat().hasStencilAspect()) {
		//	IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_STENCIL_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture, 0);
		//} else {
			IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_TEXTURE_2D, ((GlTexture) texture).glId(), 0);
		//}

		this.hasDepthAttachment = true;
	}

	public void addDepthAttachmentBypass(int texture) {
		if (IrisVulkan.isActive()) {
			this.depthTexture = texture;
			this.hasDepthAttachment = true;
			return;
		}

		int fb = getGlId();

		IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_DEPTH_ATTACHMENT, GL30C.GL_TEXTURE_2D, texture, 0);

		this.hasDepthAttachment = true;
	}

	public void addColorAttachment(int index, int texture) {
		if (!IrisVulkan.isActive()) {
			int fb = getGlId();

			IrisRenderSystem.framebufferTexture2D(fb, GL30C.GL_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0 + index, GL30C.GL_TEXTURE_2D, texture, 0);
		}

		attachments.put(index, texture);
	}

	public void noDrawBuffers() {
		if (IrisVulkan.isActive()) {
			vulkanDrawBuffers = new int[0];
			return;
		}

		IrisRenderSystem.drawBuffers(getGlId(), new int[]{GL30C.GL_NONE});
	}

	public void drawBuffers(int[] buffers) {
		int[] glBuffers = new int[buffers.length];
		int index = 0;

		if (buffers.length > maxDrawBuffers) {
			throw new IllegalArgumentException("Cannot write to more than " + maxDrawBuffers + " draw buffers on this GPU");
		}

		for (int buffer : buffers) {
			if (buffer >= maxColorAttachments) {
				throw new IllegalArgumentException("Only " + maxColorAttachments + " color attachments are supported on this GPU, but an attempt was made to write to a color attachment with index " + buffer);
			}

			glBuffers[index++] = GL30C.GL_COLOR_ATTACHMENT0 + buffer;
		}

		if (IrisVulkan.isActive()) {
			vulkanDrawBuffers = buffers.clone();
			return;
		}

		IrisRenderSystem.drawBuffers(getGlId(), glBuffers);
	}

	public void readBuffer(int buffer) {
		if (IrisVulkan.isActive()) {
			return;
		}

		IrisRenderSystem.readBuffer(getGlId(), GL30C.GL_COLOR_ATTACHMENT0 + buffer);
	}

	public int getColorAttachment(int index) {
		return attachments.get(index);
	}

	public boolean hasDepthAttachment() {
		return hasDepthAttachment;
	}

	/** Vulkan: the views written by a draw, in draw buffer order (fragment output location i -> entry i). */
	public List<GpuTextureView> vulkanColorViews() {
		List<GpuTextureView> views = new ArrayList<>(vulkanDrawBuffers.length);

		for (int buffer : vulkanDrawBuffers) {
			GpuTextureView view = VulkanTextures.levelView(attachments.get(buffer), 0);

			if (view == null) {
				throw new IllegalStateException("Framebuffer " + getGlId() + " has no texture at color attachment " + buffer);
			}

			views.add(view);
		}

		return views;
	}

	/** Vulkan: the depth attachment's view, or null. */
	@Nullable
	public GpuTextureView vulkanDepthView() {
		return hasDepthAttachment ? VulkanTextures.view(depthTexture) : null;
	}

	public void bind() {
		if (IrisVulkan.isActive()) {
			return;
		}

		GlStateManager._glBindFramebuffer(GL30C.GL_FRAMEBUFFER, getGlId());
	}

	public void bindAsReadBuffer() {
		if (IrisVulkan.isActive()) {
			return;
		}

		GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, getGlId());
	}

	public void bindAsDrawBuffer() {
		if (IrisVulkan.isActive()) {
			return;
		}

		GlStateManager._glBindFramebuffer(GL30C.GL_DRAW_FRAMEBUFFER, getGlId());
	}

	protected void destroyInternal() {
		if (IrisVulkan.isActive()) {
			return;
		}

		GlStateManager._glDeleteFramebuffers(getGlId());
	}

	public int getStatus() {
		if (IrisVulkan.isActive()) {
			return GL30C.GL_FRAMEBUFFER_COMPLETE;
		}

		bind();

		return IrisRenderSystem.checkFramebufferStatus(GL30C.GL_FRAMEBUFFER);
	}

	public int getId() {
		return getGlId();
	}
}
