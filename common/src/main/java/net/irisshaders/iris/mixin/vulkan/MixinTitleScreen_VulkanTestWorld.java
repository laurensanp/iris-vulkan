package net.irisshaders.iris.mixin.vulkan;

import net.irisshaders.iris.Iris;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vulkan port testing: -Diris.vulkanTest.world=name joins that singleplayer world from the title
 * screen, creating it first (creative, fixed seed, so frame captures stay comparable). Does nothing
 * without the property.
 */
@Mixin(TitleScreen.class)
public class MixinTitleScreen_VulkanTestWorld {
	@Unique
	private static final long IRIS_TEST_SEED = 26_03L;
	@Unique
	private static boolean iris$testWorldOpened;

	@Inject(method = "tick", at = @At("HEAD"))
	private void iris$openVulkanTestWorld(CallbackInfo ci) {
		String world = System.getProperty("iris.vulkanTest.world");

		if (world == null || iris$testWorldOpened) {
			return;
		}

		iris$testWorldOpened = true;
		Minecraft minecraft = Minecraft.getInstance();

		if (minecraft.getLevelSource().levelExists(world)) {
			Iris.logger.info("Vulkan test: joining world {}", world);
			minecraft.createWorldOpenFlows().openWorld(world, () -> minecraft.gui.setScreen(new TitleScreen()));
		} else {
			Iris.logger.info("Vulkan test: creating world {}", world);
			LevelSettings settings = new LevelSettings(world, GameType.CREATIVE, LevelSettings.DifficultySettings.DEFAULT,
				true, WorldDataConfiguration.DEFAULT);
			minecraft.createWorldOpenFlows().createFreshLevel(world, settings, new WorldOptions(IRIS_TEST_SEED, true, false),
				WorldPresets::createNormalWorldDimensions, (TitleScreen) (Object) this);
		}
	}
}
