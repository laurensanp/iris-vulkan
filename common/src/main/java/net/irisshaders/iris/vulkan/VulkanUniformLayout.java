package net.irisshaders.iris.vulkan;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The std140 block that replaces a pack's loose uniforms on Vulkan ({@code IrisUniforms}). The
 * transformer builds it from the declarations it removes; the program parses it back out of the
 * final GLSL. Each member's byte offset is what Iris treats as that uniform's "location".
 */
public final class VulkanUniformLayout {
	public static final String BLOCK_NAME = "IrisUniforms";
	private static final String HIDDEN_PREFIX = "iris_hidden_";
	public static final VulkanUniformLayout EMPTY = new VulkanUniformLayout(List.of());

	private static final Pattern BLOCK = Pattern.compile("uniform\\s+" + BLOCK_NAME + "\\s*\\{([^}]*)}");
	/** GLSL initializers of uniforms (`uniform float x = 1.0;`), by name and type, from the transform. */
	private static final Map<String, String> DEFAULTS = new ConcurrentHashMap<>();
	private static final Pattern NUMBER = Pattern.compile("true|false|[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

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
				String name = member.group(2).startsWith(HIDDEN_PREFIX) ? member.group(2).substring(HIDDEN_PREFIX.length()) : member.group(2);
				declarations.add(new Declaration(member.group(1), name, array));
			}
		}

		return build(declarations);
	}

	/** A uniform's initializer, which a uniform block member can't have: written into the block by {@link #writeDefaults}. */
	public static void rememberDefault(String name, String type, String initializer) {
		DEFAULTS.put(type + " " + name, initializer);
	}

	/**
	 * Writes remembered initializers into a fresh block. Literals and constructors of literals are
	 * supported (`1.0`, `vec3(1.0)`, `ivec2(1, 2)`); a single value fills all components like a GLSL
	 * constructor. Anything else starts at zero, as before.
	 */
	public void writeDefaults(ByteBuffer block) {
		for (Member member : members) {
			String initializer = DEFAULTS.get(member.type + " " + member.name);

			if (initializer == null) {
				continue;
			}

			List<String> values = new ArrayList<>();
			Matcher literal = NUMBER.matcher(initializer.replaceAll("\\b[iub]?vec\\d\\b|\\bmat\\d\\b", ""));

			while (literal.find()) {
				values.add(literal.group());
			}

			int components = componentCount(member.type);

			if (member.arrayLength > 0 || member.isMatrix() || components == 0 || (values.size() != 1 && values.size() != components)) {
				VulkanUnsupported.once("uniform default value " + member.type + " " + member.name + " = " + initializer + " (starts at zero)");
				continue;
			}

			boolean integer = member.type.startsWith("i") || member.type.startsWith("u") || member.type.startsWith("b") || member.type.equals("int") || member.type.equals("uint") || member.type.equals("bool");

			for (int i = 0; i < components; i++) {
				String value = values.get(values.size() == 1 ? 0 : i);
				double number = value.equals("true") ? 1 : value.equals("false") ? 0 : Double.parseDouble(value);

				if (integer) {
					block.putInt(member.offset + i * 4, (int) number);
				} else {
					block.putFloat(member.offset + i * 4, (float) number);
				}
			}
		}
	}

	private static int componentCount(String type) {
		return switch (type) {
			case "float", "int", "uint", "bool" -> 1;
			default -> type.matches("[iub]?vec[234]") ? type.charAt(type.length() - 1) - '0' : 0;
		};
	}

	public String toGlsl() {
		return toGlsl(Set.of());
	}

	/**
	 * The block, with the members in {@code hidden} under a placeholder name: a stage that declares its own
	 * global with that name (Bliss: uniform sunVec in the fragment shader, a computed sunVec in the vertex
	 * shader) keeps the block layout without the clash. {@link #parse} reads the original name back.
	 */
	public String toGlsl(Set<String> hidden) {
		StringBuilder glsl = new StringBuilder("layout(std140) uniform ").append(BLOCK_NAME).append(" {\n");

		for (Member member : members) {
			glsl.append('\t').append(member.type).append(' ').append(hidden.contains(member.name) ? HIDDEN_PREFIX + member.name : member.name);

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
