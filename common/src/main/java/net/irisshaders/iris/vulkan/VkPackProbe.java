package net.irisshaders.iris.vulkan;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.TransformPatcher;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.programs.ProgramSource;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.platform.IrisPlatformHelpers;
import org.lwjgl.system.MemoryUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Milestone 1 check: can the active shader pack's full-screen programs (deferred, composite, final)
 * go through Iris' transformer and compile to Vulkan SPIR-V? Failed programs are written to
 * iris-vk-probe/ in the game directory.
 */
public final class VkPackProbe {
	private VkPackProbe() {
	}

	public static void run() {
		ShaderPack pack = Iris.loadShaderPackForVulkan();
		if (pack == null) {
			Iris.logger.warn("[Iris VK] no shader pack loaded, nothing to check");
			return;
		}
		ProgramSet set = pack.getProgramSet(new NamespacedId("minecraft", "overworld"));
		Path dump = IrisPlatformHelpers.getInstance().getGameDir().resolve("iris-vk-probe");
		List<ProgramSource> programs = new ArrayList<>();
		for (ProgramArrayId array : new ProgramArrayId[] {ProgramArrayId.Begin, ProgramArrayId.Prepare,
			ProgramArrayId.Deferred, ProgramArrayId.Composite}) {
			for (ProgramSource source : set.getComposite(array)) {
				if (source != null && source.isValid()) {
					programs.add(source);
				}
			}
		}
		set.get(ProgramId.Final).filter(ProgramSource::isValid).ifPresent(programs::add);

		int ok = 0;
		int failed = 0;
		long start = System.nanoTime();
		for (ProgramSource source : programs) {
			String error = check(set, source, dump);
			if (error == null) {
				ok++;
			} else {
				failed++;
				if (failed <= 5) {
					Iris.logger.warn("[Iris VK] {} failed: {}", source.getName(), error.lines().limit(6).toList());
				}
			}
		}
		Iris.logger.info("[Iris VK] {}: {} of {} full-screen programs compile to SPIR-V ({} failed) in {} ms",
			Iris.getCurrentPackName(), ok, programs.size(), failed, (System.nanoTime() - start) / 1_000_000);
	}

	private static String check(ProgramSet set, ProgramSource source, Path dump) {
		Map<PatchShaderType, String> transformed;
		try {
			transformed = TransformPatcher.patchComposite(source.getName(), source.getVertexSource().orElseThrow(),
				source.getGeometrySource().orElse(null), source.getFragmentSource().orElseThrow(),
				TextureStage.COMPOSITE_AND_FINAL, set.getPackDirectives().getTextureMap(), Set.of());
		} catch (RuntimeException e) {
			return "transform: " + e;
		}
		StringBuilder errors = new StringBuilder();
		compileStage(source.getName() + ".vsh", transformed.get(PatchShaderType.VERTEX), VkShaderCompiler.VERTEX, errors, dump);
		compileStage(source.getName() + ".gsh", transformed.get(PatchShaderType.GEOMETRY), VkShaderCompiler.GEOMETRY, errors, dump);
		compileStage(source.getName() + ".fsh", transformed.get(PatchShaderType.FRAGMENT), VkShaderCompiler.FRAGMENT, errors, dump);
		return errors.isEmpty() ? null : errors.toString();
	}

	private static void compileStage(String name, String glsl, int kind, StringBuilder errors, Path dump) {
		if (glsl == null) {
			return;
		}
		VkShaderCompiler.Result result = VkShaderCompiler.compile(name, glsl, kind);
		if (result.ok()) {
			MemoryUtil.memFree(result.spirv());
			return;
		}
		errors.append(name).append(": ").append(result.error()).append('\n');
		try {
			Files.createDirectories(dump);
			Files.writeString(dump.resolve(name), glsl);
			Files.writeString(dump.resolve(name + ".error.txt"), result.error());
		} catch (IOException ignored) {
		}
	}
}
