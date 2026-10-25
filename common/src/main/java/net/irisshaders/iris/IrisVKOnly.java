package net.irisshaders.iris;

import com.mojang.blaze3d.platform.InputConstants;
import net.irisshaders.iris.compat.sodium.config.ShaderPackScreenPlaceholder;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

public class IrisVKOnly {
    public static final KeyMapping.Category irisKeybindCategory = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath("iris", "keybinds"));
    private static KeyMapping shaderpackScreenKeybind;

    private static boolean probed;

    public static void run() {
        Iris.initVulkanConfig();
        shaderpackScreenKeybind = IrisPlatformHelpers.getInstance().registerKeyBinding(new KeyMapping("iris.keybind.shaderPackSelection", InputConstants.Type.KEYBOARD, InputConstants.KEY_I, irisKeybindCategory));

    }

    public static void handleKeybinds() {
        if (!probed && Minecraft.getInstance().gui.overlay() == null) {
            probed = true;
            try {
                net.irisshaders.iris.vulkan.VkPackProbe.run();
            } catch (RuntimeException e) {
                Iris.logger.error("[Iris VK] probe failed", e);
            }
        }
        if (shaderpackScreenKeybind != null) {
            if (shaderpackScreenKeybind.consumeClick()) {
                Minecraft.getInstance().gui.setScreen(new ShaderPackScreenPlaceholder(null));
            }
        }
    }
}
