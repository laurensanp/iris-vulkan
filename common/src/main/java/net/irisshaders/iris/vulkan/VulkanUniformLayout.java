package net.irisshaders.iris.vulkan;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The std140 block that replaces a pack's loose uniforms on Vulkan ({@code IrisUniforms}). The
 * transformer builds it from the declarations it removes; the program parses it back out of the
 * final GLSL. Each member's byte offset is what Iris treats as that uniform's "location".
 */
public final class VulkanUniformLayout {
	public static final String BLOCK_NAME = "IrisUniforms";
	public static final VulkanUniformLayout EMPTY = new VulkanUniformLayout(List.of());

	private static final Pattern BLOCK = Pattern.compile("uniform\\s+" + BLOCK_NAME + "\\s*\\{([^}]*)}");
	private static final Pattern MEMBER = Pattern.compile("(\\w+)\\s+(\\w+)\\s*(?:\\[\\s*(\\d+)\\s*])?");

	private final List<Member> members;
	private final Map<String, Member> byName = new LinkedHashMap<>();
	private final int size;

	private VulkanUniformLayout(List<Member> members) {
		this.members = Collections.unmodifiableList(members);

		int end = 0;
		for (Member member : members) {
			byName.put(member.name, member);
			end = Math.max(end, member.offset + member.size());
		}

		// A uniform block is sized in multiples of a vec4.
		this.size = align(end, 16);
	}

	/** Lays out the given declarations (type, name, array length or 0) in order, std140 rules. */
	public static VulkanUniformLayout build(List<Declaration> declarations) {
		List<Member> members = new ArrayList<>();
		int offset = 0;

		for (Declaration declaration : declarations) {
			Std140 type = Std140.of(declaration.type);

			if (type == null) {
				throw new IllegalArgumentException("Uniform " + declaration.name + " has a type that can't be put in a uniform block: " + declaration.type);
			}

			int alignment = declaration.arrayLength > 0 || type.columns > 1 ? 16 : type.alignment;
			int stride = declaration.arrayLength > 0 ? align(type.size, 16) : type.size;
			offset = align(offset, alignment);
			members.add(new Member(declaration.type, declaration.name, declaration.arrayLength, offset, type, stride));
			offset += stride * Math.max(1, declaration.arrayLength);
		}

		return new VulkanUniformLayout(members);
	}

	/** Reads the layout back from transformed GLSL; {@link #EMPTY} if the stage has no such block. */
	public static VulkanUniformLayout parse(String source) {
		Matcher block = BLOCK.matcher(source);

		if (!block.find()) {
			return EMPTY;
		}

		List<Declaration> declarations = new ArrayList<>();

		for (String part : block.group(1).split(";")) {
			Matcher member = MEMBER.matcher(part.trim());

			if (member.matches()) {
				int array = member.group(3) != null ? Integer.parseInt(member.group(3)) : 0;
				declarations.add(new Declaration(member.group(1), member.group(2), array));
			}
		}

		return build(declarations);
	}

	public String toGlsl() {
		StringBuilder glsl = new StringBuilder("layout(std140) uniform ").append(BLOCK_NAME).append(" {\n");

		for (Member member : members) {
			glsl.append('\t').append(member.type).append(' ').append(member.name);

			if (member.arrayLength > 0) {
				glsl.append('[').append(member.arrayLength).append(']');
			}

			glsl.append(";\n");
		}

		return glsl.append("};").toString();
	}

	public boolean isEmpty() {
		return members.isEmpty();
	}

	public Member get(String name) {
		return byName.get(name);
	}

	public List<Member> members() {
		return members;
	}

	/** Size of the block in bytes (a multiple of 16; at least 16 so the buffer is never empty). */
	public int size() {
		return Math.max(size, 16);
	}

	static int align(int value, int alignment) {
		return (value + alignment - 1) / alignment * alignment;
	}

	public record Declaration(String type, String name, int arrayLength) {
	}

	public record Member(String type, String name, int arrayLength, int offset, Std140 layout, int stride) {
		int size() {
			return stride * Math.max(1, arrayLength);
		}

		/** Matrices are stored as columns padded to vec4. */
		public boolean isMatrix() {
			return layout.columns > 1;
		}
	}

	/** std140 base alignment and size; matrices are arrays of vec4-aligned columns. */
	public enum Std140 {
		SCALAR(4, 4, 1),
		VEC2(8, 8, 1),
		VEC3(16, 12, 1),
		VEC4(16, 16, 1),
		MAT2(16, 32, 2),
		MAT3(16, 48, 3),
		MAT4(16, 64, 4);

		final int alignment;
		final int size;
		final int columns;

		Std140(int alignment, int size, int columns) {
			this.alignment = alignment;
			this.size = size;
			this.columns = columns;
		}

		static Std140 of(String type) {
			return switch (type) {
				case "float", "int", "uint", "bool" -> SCALAR;
				case "vec2", "ivec2", "uvec2", "bvec2" -> VEC2;
				case "vec3", "ivec3", "uvec3", "bvec3" -> VEC3;
				case "vec4", "ivec4", "uvec4", "bvec4" -> VEC4;
				case "mat2", "mat2x2" -> MAT2;
				case "mat3", "mat3x3" -> MAT3;
				case "mat4", "mat4x4" -> MAT4;
				default -> null;
			};
		}
	}
}
