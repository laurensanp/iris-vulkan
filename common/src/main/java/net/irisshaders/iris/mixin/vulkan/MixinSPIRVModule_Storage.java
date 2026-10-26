package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.frontend.shaders.SPIRVModule;
import net.irisshaders.iris.vulkan.DescriptorAs2D;
import net.irisshaders.iris.vulkan.VulkanStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.nio.IntBuffer;
import java.util.List;

/**
 * What RenderPearl's pipeline builder sees of a shader's descriptors:
 * <ul>
 *     <li>descriptor set 1 (storage images / buffers, see VulkanStorage) is ours: not listed, so the
 *     builder neither rejects those names nor moves them into set 0;</li>
 *     <li>1D and 3D samplers are listed as 2D. They are combined image samplers all the same, which is
 *     all the bind group layout says; the view bound decides the dimension (MixinVulkanGpuTextureView_Dimension).</li>
 * </ul>
 */
@Mixin(SPIRVModule.class)
public class MixinSPIRVModule_Storage {
	/** SpvDim1D / SpvDim3D */
	private static final int DIM_1D = 0;
	private static final int DIM_3D = 2;

	@WrapOperation(method = "doReflection", at = @At(value = "INVOKE",
		target = "Lcom/mojang/renderpearl/frontend/shaders/SPIRVModule;generateDescriptorList(JJLjava/nio/IntBuffer;I)Ljava/util/List;"))
	private List<SpvModule.Reflection.Descriptor> iris$adaptDescriptors(long context, long resources, IntBuffer count, int type,
																		 Operation<List<SpvModule.Reflection.Descriptor>> original) {
		List<SpvModule.Reflection.Descriptor> descriptors = original.call(context, resources, count, type);

		if (descriptors.stream().noneMatch(descriptor -> descriptor.descriptorSetIndex() == VulkanStorage.SET
			|| descriptor.type().dimensions() == DIM_1D || descriptor.type().dimensions() == DIM_3D)) {
			return descriptors;
		}

		return descriptors.stream()
			.filter(descriptor -> descriptor.descriptorSetIndex() != VulkanStorage.SET)
			.map(descriptor -> descriptor.type().dimensions() == DIM_1D || descriptor.type().dimensions() == DIM_3D
				? new DescriptorAs2D(descriptor) : descriptor)
			.toList();
	}
}
