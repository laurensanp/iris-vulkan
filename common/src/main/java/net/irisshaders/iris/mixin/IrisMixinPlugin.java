package net.irisshaders.iris.mixin;

import com.google.common.base.Splitter;
import com.google.common.io.Files;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import net.irisshaders.iris.vulkan.VulkanGlGuardTransformer;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class IrisMixinPlugin implements IMixinConfigPlugin {
    private static final Splitter OPTION_SPLITTER = Splitter.on(':').limit(2);

    public static boolean usingVulkan;

    static {
        BufferedReader reader = null;
        boolean check = true;
        try {
            reader = Files.newReader(IrisPlatformHelpers.getInstance().getGameDir().resolve("options.txt").toFile(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException e) {
            usingVulkan = false;
            check = false;
        }

        if (check) {
            Map<String, String> options = new HashMap<>();

            try {
                reader.lines().forEach(line -> {
                    try {
                        Iterator<String> iterator = OPTION_SPLITTER.split(line).iterator();
                        options.put((String) iterator.next(), (String) iterator.next());
                    } catch (Exception var3) {
                    }
                });
            } catch (Throwable var6) {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (Throwable var5) {
                        var6.addSuppressed(var5);
                    }
                }

                throw var6;
            }

            if (options.get("preferredGraphicsBackend") != null) {
                usingVulkan = options.get("preferredGraphicsBackend").toLowerCase(Locale.ROOT).contains("vulkan");
            } else {
                usingVulkan = false;
            }
        }

        if (!usingVulkan && irisDlssForcesVulkan()) {
            usingVulkan = true;
        }
    }

    /**
     * Iris DLSS starts the game on Vulkan whatever preferredGraphicsBackend says, unless DLSS is
     * turned off in its config. Mixins are chosen before it runs, so read the same setting here.
     */
    private static boolean irisDlssForcesVulkan() {
        if (!IrisPlatformHelpers.getInstance().isModLoaded("iris-dlss")) {
            return false;
        }

        Properties properties = new Properties();
        java.nio.file.Path config = IrisPlatformHelpers.getInstance().getConfigDir().resolve("iris-dlss.properties");

        if (java.nio.file.Files.exists(config)) {
            try (BufferedReader configReader = java.nio.file.Files.newBufferedReader(config, StandardCharsets.UTF_8)) {
                properties.load(configReader);
            } catch (java.io.IOException e) {
                return true;
            }
        }

        return Boolean.parseBoolean(properties.getProperty("enabled", "true"));
    }
	@Override
	public void onLoad(String mixinPackage) {

	}

	@Override
	public String getRefMapperConfig() {
		return "iris.refmap.json";
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		// The Vulkan port runs the whole of Iris on both backends; the VKOnly placeholder (a screen
		// asking to switch to OpenGL) is not needed any more.
		return !mixinClassName.contains("VKOnly");
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {

	}

	@Override
	public List<String> getMixins() {
		return List.of();
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		//if (targetClassName.contains("LevelRenderer")) {
		//	targetClass.methods.forEach(m -> System.out.println(m.name + m.desc));
		//}
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
		if (usingVulkan && targetClassName.equals(VulkanGlGuardTransformer.TARGET)) {
			VulkanGlGuardTransformer.transform(targetClass);
		}
	}
}
