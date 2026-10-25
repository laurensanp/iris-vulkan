package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vulkan port testing, together with -Diris.vulkanTest.world: once in the world, saves a screenshot
 * of the main render target (screenshots/vulkan-test.png) after iris.vulkanTest.captureTicks client
 * ticks (default 200, ten seconds) and then closes the game. Works without Frame Generation.
 */
@Mixin(Minecraft.class)
public class MixinMinecraft_VulkanTestCapture {
	@Unique
	private static final int IRIS_CAPTURE_TICKS = Integer.getInteger("iris.vulkanTest.captureTicks", 200);
	@Unique
	private int iris$ticksInWorld;

	@Inject(method = "tick", at = @At("TAIL"))
	private void iris$captureVulkanTest(CallbackInfo ci) {
		Minecraft minecraft = (Minecraft) (Object) this;

		if (System.getProperty("iris.vulkanTest.world") == null || minecraft.level == null || minecraft.player == null) {
			return;
		}

		iris$ticksInWorld++;

		if (iris$ticksInWorld == IRIS_CAPTURE_TICKS) {
			Screenshot.grab(minecraft.gameDirectory, "vulkan-test.png", minecraft.gameRenderer.mainRenderTarget(), 1,
				message -> Iris.logger.info("Vulkan test: {}", message.getString()));
		} else if (iris$ticksInWorld == IRIS_CAPTURE_TICKS + 40) {
			Iris.logger.info("Vulkan test: done, closing the game");
			minecraft.stop();
		}
	}
}
