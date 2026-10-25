package net.irisshaders.iris.vulkan;

import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;

/**
 * Compiles shader pack GLSL (after Iris' transformer) to Vulkan SPIR-V. Shader packs are written
 * against OpenGL: loose uniforms, implicit bindings. glslang's relaxed Vulkan rules accept that and
 * pack loose uniforms into one default uniform block; bindings and locations are assigned
 * automatically and read back through reflection.
 */
public final class VkShaderCompiler {
	public static final int VERTEX = Shaderc.shaderc_glsl_vertex_shader;
	public static final int FRAGMENT = Shaderc.shaderc_glsl_fragment_shader;
	public static final int GEOMETRY = Shaderc.shaderc_glsl_geometry_shader;
	public static final int COMPUTE = Shaderc.shaderc_glsl_compute_shader;

	private static long compiler;

	private VkShaderCompiler() {
	}

	/**
	 * Iris' layout transformer gives every stage input/output an explicit location; before GLSL 4.10
	 * that needs ARB_separate_shader_objects (as RenderPearl's own Vulkan shaders use).
	 */
	static String enableVulkanExtensions(String source) {
		int version = source.indexOf("#version");
		if (version < 0) {
			return source;
		}
		int lineEnd = source.indexOf('\n', version);
		if (lineEnd < 0) {
			return source;
		}
		return source.substring(0, lineEnd + 1) + "#extension GL_ARB_separate_shader_objects : enable\n"
			+ source.substring(lineEnd + 1);
	}

	/** SPIR-V (caller frees with MemoryUtil.memFree) or the compiler's error message. */
	public record Result(ByteBuffer spirv, String error) {
		public boolean ok() {
			return spirv != null;
		}
	}

	/**
	 * First binding of a stage's resources; stages get separate ranges of 64 (each has its own uniform
	 * block). Within a range every resource gets its own number (see renumberBindings).
	 */
	public static int bindingBase(int kind) {
		return kind == FRAGMENT ? 128 : kind == GEOMETRY ? 64 : 0;
	}

	public static final int STAGE_BINDINGS = 64;

	/**
	 * Gives every Binding decoration (OpDecorate %id Binding n) of a SPIR-V module its own number from
	 * base on: glslang numbers textures, images and buffers each from 0, Vulkan has one namespace.
	 */
	static void renumberBindings(ByteBuffer spirv, int base) {
		int next = base;
		java.nio.IntBuffer words = spirv.asIntBuffer();
		int position = 5; // header
		while (position < words.limit()) {
			int instruction = words.get(position);
			int wordCount = instruction >>> 16;
			int opcode = instruction & 0xFFFF;
			if (opcode == 71 && wordCount == 4 && words.get(position + 2) == 33) {
				words.put(position + 3, next++);
			}
			if (wordCount == 0) {
				break;
			}
			position += wordCount;
		}
	}

	public static synchronized Result compile(String name, String source, int kind) {
		source = enableVulkanExtensions(source);
		if (compiler == 0L) {
			compiler = Shaderc.shaderc_compiler_initialize();
		}
		long options = Shaderc.shaderc_compile_options_initialize();
		try {
			Shaderc.shaderc_compile_options_set_target_env(options, Shaderc.shaderc_target_env_vulkan,
				Shaderc.shaderc_env_version_vulkan_1_2);
			Shaderc.shaderc_compile_options_set_vulkan_rules_relaxed(options, true);
			Shaderc.shaderc_compile_options_set_auto_bind_uniforms(options, true);
			Shaderc.shaderc_compile_options_set_auto_map_locations(options, true);
			Shaderc.shaderc_compile_options_set_auto_combined_image_sampler(options, true);
			// No optimizer: it would strip the names reflection maps uniforms and samplers by.
			Shaderc.shaderc_compile_options_set_optimization_level(options, Shaderc.shaderc_optimization_level_zero);
			long result = Shaderc.shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
			try {
				if (Shaderc.shaderc_result_get_compilation_status(result) != Shaderc.shaderc_compilation_status_success) {
					return new Result(null, Shaderc.shaderc_result_get_error_message(result));
				}
				ByteBuffer bytes = Shaderc.shaderc_result_get_bytes(result);
				ByteBuffer copy = MemoryUtil.memAlloc(bytes.remaining());
				MemoryUtil.memCopy(bytes, copy);
				renumberBindings(copy, bindingBase(kind));
				return new Result(copy, null);
			} finally {
				Shaderc.shaderc_result_release(result);
			}
		} finally {
			Shaderc.shaderc_compile_options_release(options);
		}
	}
}
