package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.frontend.shaders.PipelineBuilder;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.irisshaders.iris.vulkan.VulkanStages;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/**
 * RenderPearl checks that the vertex shader writes every input of the fragment shader and refuses some
 * types there (structs, which Photon passes between stages). For Iris' programs the check sees no
 * interface: LayoutTransformer gives both sides matching locations, and with geometry / tessellation
 * stages in between (VulkanStages) vertex outputs and fragment inputs don't match at all.
 */
@Mixin(PipelineBuilder.class)
public class MixinPipelineBuilder_Stages {
	@WrapOperation(method = "generateBackendCreateInfo", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/frontend/shaders/PipelineBuilder;generateSlotMap(Ljava/util/List;Ljava/lang/String;Lcom/mojang/renderpearl/api/pipeline/ShaderType;)Lit/unimi/dsi/fastutil/ints/Int2ObjectMap;"))
	private Int2ObjectMap<?> iris$noInterfaceCheckForIris(List<?> variables, String name, ShaderType type, Operation<Int2ObjectMap<?>> original,
																@Local(argsOnly = true) RenderPipeline pipeline) {
		if (pipeline.getLocation().getNamespace().equals("iris") || VulkanStages.get(pipeline.getLocation().toString()) != null) {
			return new Int2ObjectOpenHashMap<>();
		}

		return original.call(variables, name, type);
	}
}
