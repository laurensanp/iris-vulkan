package net.irisshaders.iris.mixin.vulkan;

import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.frontend.FrontendRenderPass;
import net.irisshaders.iris.vulkan.VulkanGbufferPass;
import net.irisshaders.iris.vulkan.VulkanGbuffers;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashMap;
import java.util.HashSet;

/** Vulkan gbuffers: pipelines set in a redirected pass are swapped for ones built for its attachments. */
@Mixin(FrontendRenderPass.class)
public class MixinFrontendRenderPass_Gbuffers implements VulkanGbufferPass {
	@Shadow
	@Final
	protected HashMap<String, Object> uniforms;

	@Unique
	@Nullable
	private VulkanGbuffers.Target iris$gbufferTarget;

	@Override
	public void iris$setGbufferTarget(@Nullable VulkanGbuffers.Target target) {
		this.iris$gbufferTarget = target;
	}

	@ModifyVariable(method = "setPipeline", at = @At("HEAD"), argsOnly = true)
	private CompiledRenderPipeline iris$useGbufferVariant(CompiledRenderPipeline pipeline) {
		return iris$gbufferTarget == null ? pipeline : VulkanGbuffers.adapt(pipeline, iris$gbufferTarget);
	}

	@Unique
	@Nullable
	private CompiledRenderPipeline iris$pipeline;

	@Inject(method = "setPipeline", at = @At("RETURN"))
	private void iris$bindProgramResources(CompiledRenderPipeline pipeline, CallbackInfo ci) {
		if (iris$gbufferTarget != null) {
			iris$pipeline = pipeline;
			VulkanGbuffers.afterSetPipeline((RenderPass) (Object) this, pipeline, new HashSet<>(uniforms.keySet()));
		}
	}

	@Inject(method = "setUniform(Ljava/lang/String;Lcom/mojang/renderpearl/api/textures/GpuTextureView;Lcom/mojang/renderpearl/api/textures/GpuSampler;)V", at = @At("RETURN"))
	private void iris$albedoBound(String name, GpuTextureView view, GpuSampler sampler, CallbackInfo ci) {
		if (iris$gbufferTarget != null && view != null && (name.equals("Sampler0") || name.equals("u_BlockTex"))) {
			VulkanGbuffers.afterAlbedoBound((RenderPass) (Object) this, iris$pipeline);
		}
	}
}
