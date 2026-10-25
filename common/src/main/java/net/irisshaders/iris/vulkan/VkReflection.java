package net.irisshaders.iris.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.spvc.Spv;
import org.lwjgl.util.spvc.Spvc;
import org.lwjgl.util.spvc.SpvcReflectedResource;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * What a SPIR-V stage needs from the outside (SPIRV-Cross reflection): the default uniform block's
 * members (loose uniforms, packed by glslang's relaxed Vulkan rules), sampled and storage images
 * with their bindings, vertex inputs and fragment outputs with their locations.
 */
public final class VkReflection {
	/** A loose uniform inside a stage's default uniform block. */
	public record Member(String name, int offset, int baseType, int vectorSize, int columns, int arraySize,
						 int arrayStride, int matrixStride) {
		public boolean isFloat() {
			return baseType == Spvc.SPVC_BASETYPE_FP32;
		}
	}

	/** A uniform block (binding) and its members; size in bytes. */
	public record Block(String name, int binding, int size, List<Member> members) {
	}

	/** A texture or image: sampled (combined image sampler) or storage. */
	public record Image(String name, int binding, int dimension, boolean storage) {
	}

	/** A stage input or output with its location. */
	public record Variable(String name, int location, int vectorSize) {
	}

	public final List<Block> blocks = new ArrayList<>();
	public final List<Image> images = new ArrayList<>();
	public final List<Variable> inputs = new ArrayList<>();
	public final List<Variable> outputs = new ArrayList<>();
	public final List<String> storageBuffers = new ArrayList<>();

	private VkReflection() {
	}

	public static VkReflection of(ByteBuffer spirv) {
		VkReflection reflection = new VkReflection();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer pointer = stack.mallocPointer(1);
			check(Spvc.spvc_context_create(pointer), "context");
			long context = pointer.get(0);
			try {
				IntBuffer words = spirv.asIntBuffer();
				check(Spvc.spvc_context_parse_spirv(context, words, words.remaining(), pointer), "parse");
				long parsed = pointer.get(0);
				check(Spvc.spvc_context_create_compiler(context, Spvc.SPVC_BACKEND_NONE, parsed,
					Spvc.SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, pointer), "compiler");
				long compiler = pointer.get(0);
				check(Spvc.spvc_compiler_create_shader_resources(compiler, pointer), "resources");
				long resources = pointer.get(0);

				for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_UNIFORM_BUFFER, stack)) {
					reflection.blocks.add(block(compiler, resource, stack));
				}
				for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_SAMPLED_IMAGE, stack)) {
					reflection.images.add(image(compiler, resource, false));
				}
				for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_IMAGE, stack)) {
					reflection.images.add(image(compiler, resource, true));
				}
				for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_STORAGE_BUFFER, stack)) {
					reflection.storageBuffers.add(resource.nameString());
				}
				for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_INPUT, stack)) {
					reflection.inputs.add(variable(compiler, resource));
				}
				for (SpvcReflectedResource resource : list(resources, Spvc.SPVC_RESOURCE_TYPE_STAGE_OUTPUT, stack)) {
					reflection.outputs.add(variable(compiler, resource));
				}
			} finally {
				Spvc.spvc_context_destroy(context);
			}
		}
		return reflection;
	}

	private static List<SpvcReflectedResource> list(long resources, int type, MemoryStack stack) {
		PointerBuffer list = stack.mallocPointer(1);
		PointerBuffer count = stack.mallocPointer(1);
		check(Spvc.spvc_resources_get_resource_list_for_type(resources, type, list, count), "resource list");
		SpvcReflectedResource.Buffer buffer = SpvcReflectedResource.create(list.get(0), (int) count.get(0));
		List<SpvcReflectedResource> out = new ArrayList<>();
		for (int i = 0; i < buffer.capacity(); i++) {
			out.add(buffer.get(i));
		}
		return out;
	}

	private static Block block(long compiler, SpvcReflectedResource resource, MemoryStack stack) {
		int binding = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationBinding);
		long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.base_type_id());
		PointerBuffer size = stack.mallocPointer(1);
		check(Spvc.spvc_compiler_get_declared_struct_size(compiler, type, size), "struct size");
		IntBuffer value = stack.mallocInt(1);
		List<Member> members = new ArrayList<>();
		int count = Spvc.spvc_type_get_num_member_types(type);
		for (int i = 0; i < count; i++) {
			String name = Spvc.spvc_compiler_get_member_name(compiler, resource.base_type_id(), i);
			long memberType = Spvc.spvc_compiler_get_type_handle(compiler, Spvc.spvc_type_get_member_type(type, i));
			Spvc.spvc_compiler_type_struct_member_offset(compiler, type, i, value);
			int offset = value.get(0);
			int arraySize = 1;
			int arrayStride = 0;
			if (Spvc.spvc_type_get_num_array_dimensions(memberType) > 0) {
				arraySize = Spvc.spvc_type_get_array_dimension(memberType, 0);
				Spvc.spvc_compiler_type_struct_member_array_stride(compiler, type, i, value);
				arrayStride = value.get(0);
			}
			int columns = Spvc.spvc_type_get_columns(memberType);
			int matrixStride = 0;
			if (columns > 1) {
				Spvc.spvc_compiler_type_struct_member_matrix_stride(compiler, type, i, value);
				matrixStride = value.get(0);
			}
			members.add(new Member(name, offset, Spvc.spvc_type_get_basetype(memberType),
				Spvc.spvc_type_get_vector_size(memberType), columns, arraySize, arrayStride, matrixStride));
		}
		return new Block(resource.nameString(), binding, (int) size.get(0), members);
	}

	private static Image image(long compiler, SpvcReflectedResource resource, boolean storage) {
		int binding = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationBinding);
		long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
		return new Image(resource.nameString(), binding, Spvc.spvc_type_get_image_dimension(type), storage);
	}

	private static Variable variable(long compiler, SpvcReflectedResource resource) {
		int location = Spvc.spvc_compiler_get_decoration(compiler, resource.id(), Spv.SpvDecorationLocation);
		long type = Spvc.spvc_compiler_get_type_handle(compiler, resource.type_id());
		return new Variable(resource.nameString(), location, Spvc.spvc_type_get_vector_size(type));
	}

	private static void check(int result, String what) {
		if (result != Spvc.SPVC_SUCCESS) {
			throw new IllegalStateException("SPIRV-Cross " + what + " failed: " + result);
		}
	}

	public int uniformCount() {
		return blocks.stream().mapToInt(block -> block.members().size()).sum();
	}
}
