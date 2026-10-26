package net.irisshaders.iris.mixin.vulkan;

import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.backend.vulkan.VulkanBackend;
import com.mojang.renderpearl.backend.vulkan.VulkanPhysicalDevice;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.vulkan.VulkanRaw;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VK10;
import org.lwjgl.vulkan.VkPhysicalDeviceFeatures;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Shader packs need device features the game doesn't ask for: geometry / tessellation shaders and
 * image / buffer stores from vertex and fragment shaders (voxelization in the shadow pass). Each is
 * turned on if the GPU has it, in the feature struct before it goes into VkDeviceCreateInfo (iris-dlss
 * replaces the vkCreateDevice call itself, so this must happen earlier).
 */
@Mixin(VulkanBackend.class)
public class MixinVulkanBackend_Features {
	@ModifyArg(
		method = "createDevice(Lcom/mojang/renderpearl/backend/vulkan/init/FeatureSet;Lcom/mojang/renderpearl/backend/vulkan/VulkanPhysicalDevice;)Lorg/lwjgl/vulkan/VkDevice;",
		at = @At(value = "INVOKE", target = "Lorg/lwjgl/vulkan/VkDeviceCreateInfo;pEnabledFeatures(Lorg/lwjgl/vulkan/VkPhysicalDeviceFeatures;)Lorg/lwjgl/vulkan/VkDeviceCreateInfo;"))
	private static VkPhysicalDeviceFeatures iris$enablePackFeatures(VkPhysicalDeviceFeatures enabled, @Local(argsOnly = true) VulkanPhysicalDevice physicalDevice) {
		if (enabled == null) {
			return null;
		}

		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkPhysicalDeviceFeatures available = VkPhysicalDeviceFeatures.calloc(stack);
			VK10.vkGetPhysicalDeviceFeatures(physicalDevice.vkPhysicalDevice(), available);

			VulkanRaw.geometryShader = available.geometryShader();
			VulkanRaw.tessellationShader = available.tessellationShader();
			VulkanRaw.vertexStores = available.vertexPipelineStoresAndAtomics();
			VulkanRaw.fragmentStores = available.fragmentStoresAndAtomics();

			enabled.geometryShader(enabled.geometryShader() || available.geometryShader());
			enabled.tessellationShader(enabled.tessellationShader() || available.tessellationShader());
			enabled.vertexPipelineStoresAndAtomics(enabled.vertexPipelineStoresAndAtomics() || available.vertexPipelineStoresAndAtomics());
			enabled.fragmentStoresAndAtomics(enabled.fragmentStoresAndAtomics() || available.fragmentStoresAndAtomics());
			enabled.shaderStorageImageExtendedFormats(enabled.shaderStorageImageExtendedFormats() || available.shaderStorageImageExtendedFormats());
			enabled.shaderStorageImageReadWithoutFormat(enabled.shaderStorageImageReadWithoutFormat() || available.shaderStorageImageReadWithoutFormat());
			enabled.shaderStorageImageWriteWithoutFormat(enabled.shaderStorageImageWriteWithoutFormat() || available.shaderStorageImageWriteWithoutFormat());
			enabled.shaderImageGatherExtended(enabled.shaderImageGatherExtended() || available.shaderImageGatherExtended());
			enabled.independentBlend(enabled.independentBlend() || available.independentBlend());

			Iris.logger.info("Vulkan device features for shader packs: geometry {}, tessellation {}, vertex stores {}, fragment stores {}",
				VulkanRaw.geometryShader, VulkanRaw.tessellationShader, VulkanRaw.vertexStores, VulkanRaw.fragmentStores);
		}

		return enabled;
	}
}
