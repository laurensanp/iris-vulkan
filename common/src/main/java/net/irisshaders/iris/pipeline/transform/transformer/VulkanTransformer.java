package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.DeclarationExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.external_declaration.ExternalDeclaration;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.LayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.NamedLayoutQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.StorageQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifier;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.TypeQualifierPart;
import io.github.douira.glsl_transformer.ast.node.type.specifier.ArraySpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinFixedTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.BuiltinNumericTypeSpecifier;
import io.github.douira.glsl_transformer.ast.node.type.specifier.TypeSpecifier;
import io.github.douira.glsl_transformer.ast.print.ASTPrinter;
import io.github.douira.glsl_transformer.ast.query.Root;
import io.github.douira.glsl_transformer.ast.transform.ASTInjectionPoint;
import io.github.douira.glsl_transformer.ast.transform.ASTParser;
import io.github.douira.glsl_transformer.util.Type;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.transform.Patch;
import net.irisshaders.iris.pipeline.transform.PatchShaderType;
import net.irisshaders.iris.pipeline.transform.parameter.Parameters;
import net.irisshaders.iris.vulkan.VulkanUniformLayout;
import net.irisshaders.iris.vulkan.VulkanUnsupported;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Last step for programs that run on Vulkan. RenderPearl compiles GLSL for Vulkan, where uniforms
 * outside a block are not allowed: every non-opaque uniform of every stage goes into one std140
 * block ({@link VulkanUniformLayout#BLOCK_NAME}), declared identically in all stages so they share
 * one buffer. Samplers stay loose (RenderPearl binds them by name). GL-only built-ins are renamed.
 * Varying locations are done before this by {@link LayoutTransformer}.
 */
public class VulkanTransformer {
	/**
	 * gbuffers programs read vanilla's uniform blocks and samplers. GL Iris finds them under its own
	 * names (IrisBindings); RenderPearl binds by name, so on Vulkan they get vanilla's names.
	 */
	private static final Map<String, String> VANILLA_BINDINGS = Map.of(
		"iris_DynamicTransforms", "DynamicTransforms",
		"iris_Projection", "Projection",
		"iris_Globals", "Globals",
		"iris_Fog", "Fog",
		"iris_CloudInfo", "CloudInfo",
		"iris_Lighting", "Lighting",
		"gtexture", "Sampler0",
		"iris_overlay", "Sampler1",
		"lightmap", "Sampler2");
	/** The same for Sodium's terrain pipelines (u_Globals already has Sodium's name). */
	private static final Map<String, String> SODIUM_BINDINGS = Map.of(
		"gtexture", "u_BlockTex",
		"lightmap", "u_LightTex");

	public static void transformGrouped(ASTParser t, Map<PatchShaderType, TranslationUnit> trees, Parameters parameters) {
		Map<String, VulkanUniformLayout.Declaration> uniforms = new LinkedHashMap<>();

		for (TranslationUnit tree : trees.values()) {
			if (tree == null) {
				continue;
			}

			tree.getRoot().indexBuildSession(root -> collectUniforms(tree, root, uniforms));
		}

		VulkanUniformLayout layout = VulkanUniformLayout.build(new ArrayList<>(uniforms.values()));

		for (TranslationUnit tree : trees.values()) {
			if (tree != null) {
				tree.getRoot().indexBuildSession(root -> stubUnbindableSamplers(t, tree, root));
			}
		}

		TranslationUnit vertex = trees.get(PatchShaderType.VERTEX);

		if (vertex != null) {
			vertex.getRoot().indexBuildSession(root -> locateVertexInputs(root));
		}

		for (TranslationUnit tree : trees.values()) {
			if (tree == null) {
				continue;
			}

			tree.getRoot().indexBuildSession(root -> {
				if (!layout.isEmpty()) {
					tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, layout.toGlsl());
				}

				root.rename("gl_VertexID", "gl_VertexIndex");
				root.rename("gl_InstanceID", "gl_InstanceIndex");

				if (parameters.patch == Patch.VANILLA) {
					renameBindings(root, VANILLA_BINDINGS);
				} else if (parameters.patch == Patch.SODIUM) {
					renameBindings(root, SODIUM_BINDINGS);
				}
			});
		}
	}

	/** Gives Iris' names the binding names, first moving aside pack identifiers that already use them (e.g. Solas' Fog()). */
	private static void renameBindings(Root root, Map<String, String> bindings) {
		bindings.forEach((from, to) -> {
			if (root.identifierIndex.has(from) && root.identifierIndex.has(to)) {
				root.rename(to, "iris_pack_" + to);
			}

			root.rename(from, to);
		});
	}

	private static void collectUniforms(TranslationUnit tree, Root root, Map<String, VulkanUniformLayout.Declaration> uniforms) {
		List<ExternalDeclaration> toRemove = new ArrayList<>();

		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!(external.getDeclaration() instanceof TypeAndInitDeclaration declaration)) {
				continue;
			}

			TypeSpecifier specifier = declaration.getType().getTypeSpecifier();

			if (!isUniform(declaration.getType().getTypeQualifier()) || !(specifier instanceof BuiltinNumericTypeSpecifier numeric)) {
				// Samplers, images, blocks and everything that isn't a uniform stay as they are.
				continue;
			}

			String type = numeric.type.getMostCompactName();
			int typeArray = arrayLength(specifier.getArraySpecifier());

			for (DeclarationMember member : declaration.getMembers()) {
				String name = member.getName().getName();
				int array = typeArray > 0 ? typeArray : arrayLength(member.getArraySpecifier());

				if (member.getInitializer() != null) {
					// A block member can't have an initializer; the program writes the value into its block instead.
					VulkanUniformLayout.rememberDefault(name, type, ASTPrinter.printCompact(member.getInitializer()));
				}

				VulkanUniformLayout.Declaration previous = uniforms.putIfAbsent(name, new VulkanUniformLayout.Declaration(type, name, array));

				if (previous != null && !previous.type().equals(type)) {
					throw new IllegalStateException("Uniform " + name + " is declared as " + previous.type() + " and as " + type + " in different stages");
				}
			}

			toRemove.add(external);
		}

		tree.getChildren().removeAll(toRemove);
		for (ExternalDeclaration declaration : toRemove) {
			declaration.detachParent();
		}
	}

	/**
	 * RenderPearl binds only 2D/cube samplers: 3D samplers and storage images (voxel / flood-fill
	 * lighting, needs the native layer, M3) become a constant of a dummy struct, and overloads of the
	 * texture/image functions on that struct return zero. Packs read "no data" instead of failing to
	 * compile. Arrays of such samplers are left alone (and still fail).
	 */
	private static void stubUnbindableSamplers(ASTParser t, TranslationUnit tree, Root root) {
		Set<String> stubTypes = new LinkedHashSet<>();
		StringBuilder constants = new StringBuilder();
		List<ExternalDeclaration> toRemove = new ArrayList<>();

		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!(external.getDeclaration() instanceof TypeAndInitDeclaration declaration)
				|| !isUniform(declaration.getType().getTypeQualifier())
				|| !(declaration.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier fixed)
				|| fixed.getArraySpecifier() != null) {
				continue;
			}

			String stub = stubType(fixed.type);

			if (stub == null || declaration.getMembers().stream().anyMatch(member -> member.getArraySpecifier() != null)) {
				continue;
			}

			stubTypes.add(stub);

			for (DeclarationMember member : declaration.getMembers()) {
				String name = member.getName().getName();
				VulkanUnsupported.once("3D samplers / images, reading zero instead (" + name + ")");
				constants.append("const ").append(stub).append(' ').append(name).append(" = ").append(stub).append("(0);\n");
			}

			toRemove.add(external);
		}

		if (toRemove.isEmpty()) {
			return;
		}

		tree.getChildren().removeAll(toRemove);
		for (ExternalDeclaration declaration : toRemove) {
			declaration.detachParent();
		}

		StringBuilder functions = new StringBuilder();

		// Every declaration is parsed as a node of its own: a string holding several keeps only the first.
		for (String stub : stubTypes) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, "struct " + stub + " { int unused; };");
			appendStubFunctions(functions, stub);
		}

		// The constants are parsed once the structs are in the tree (the parser must know the type names).
		for (String constant : constants.toString().split("\n")) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, constant);
		}
		for (String function : functions.toString().split("\n")) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, function);
		}
	}

	/** IrisStub + result kind (F/I/U) + dimension, or null for samplers RenderPearl can bind. */
	private static String stubType(BuiltinFixedTypeSpecifier.BuiltinType type) {
		return switch (type) {
			case SAMPLER3D, IMAGE3D -> "IrisStubF3";
			case ISAMPLER3D, IIMAGE3D -> "IrisStubI3";
			case USAMPLER3D, UIMAGE3D -> "IrisStubU3";
			case IMAGE2D -> "IrisStubF2";
			case IIMAGE2D -> "IrisStubI2";
			case UIMAGE2D -> "IrisStubU2";
			case IMAGE1D -> "IrisStubF1";
			case IIMAGE1D -> "IrisStubI1";
			case UIMAGE1D -> "IrisStubU1";
			default -> null;
		};
	}

	private static void appendStubFunctions(StringBuilder out, String stub) {
		String prefix = switch (stub.charAt(8)) {
			case 'I' -> "i";
			case 'U' -> "u";
			default -> "";
		};
		int dimension = stub.charAt(9) - '0';
		String result = prefix + "vec4";
		String zero = result + "(0)";
		String coord = dimension == 1 ? "float" : "vec" + dimension;
		String texel = dimension == 1 ? "int" : "ivec" + dimension;
		String one = texel + "(1)";

		out.append(result).append(" texture(").append(stub).append(" s, ").append(coord).append(" p) { return ").append(zero).append("; }\n");
		out.append(result).append(" texture(").append(stub).append(" s, ").append(coord).append(" p, float b) { return ").append(zero).append("; }\n");
		out.append(result).append(" textureLod(").append(stub).append(" s, ").append(coord).append(" p, float l) { return ").append(zero).append("; }\n");
		out.append(result).append(" texelFetch(").append(stub).append(" s, ").append(texel).append(" p, int l) { return ").append(zero).append("; }\n");
		out.append(texel).append(" textureSize(").append(stub).append(" s, int l) { return ").append(one).append("; }\n");
		out.append(result).append(" imageLoad(").append(stub).append(" s, ").append(texel).append(" p) { return ").append(zero).append("; }\n");
		out.append("void imageStore(").append(stub).append(" s, ").append(texel).append(" p, ").append(result).append(" v) { }\n");
		out.append(texel).append(" imageSize(").append(stub).append(" s) { return ").append(one).append("; }\n");
	}

	/**
	 * Vertex attributes need a location in SPIR-V. RenderPearl matches them to the vertex buffer by
	 * name, so any distinct locations do; attributes that already have one keep it.
	 */
	private static void locateVertexInputs(Root root) {
		int next = 0;

		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (external.getDeclaration() instanceof TypeAndInitDeclaration declaration
				&& hasStorage(declaration.getType().getTypeQualifier(), StorageQualifier.StorageType.IN)) {
				next = Math.max(next, explicitLocation(declaration.getType().getTypeQualifier()) + locationSlots(declaration));
			}
		}

		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (!(external.getDeclaration() instanceof TypeAndInitDeclaration declaration)) {
				continue;
			}

			TypeQualifier qualifier = declaration.getType().getTypeQualifier();

			if (!hasStorage(qualifier, StorageQualifier.StorageType.IN) || explicitLocation(qualifier) >= 0) {
				continue;
			}

			if (declaration.getMembers().size() != 1) {
				throw new IllegalStateException("Vertex inputs declared together (" + declaration.getMembers().size()
					+ " in one declaration) are not supported on Vulkan yet");
			}

			qualifier.getChildren().add(0, new LayoutQualifier(Stream.of(new NamedLayoutQualifierPart(
				new Identifier("location"), new LiteralExpression(Type.INT32, next)))));
			next += locationSlots(declaration);
		}
	}

	/** Locations a vertex input uses: one per matrix column, one otherwise. */
	private static int locationSlots(TypeAndInitDeclaration declaration) {
		if (declaration.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier numeric && numeric.type.isMatrix()) {
			return numeric.type.getDimensions()[0];
		}

		return 1;
	}

	private static int explicitLocation(TypeQualifier qualifier) {
		if (qualifier == null) {
			return -1;
		}

		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				for (LayoutQualifierPart layoutPart : layout.getParts()) {
					if (layoutPart instanceof NamedLayoutQualifierPart named && named.getName().getName().equals("location")
						&& named.getExpression() instanceof LiteralExpression literal) {
						return (int) literal.getInteger();
					}
				}
			}
		}

		return -1;
	}

	private static boolean isUniform(TypeQualifier qualifier) {
		return hasStorage(qualifier, StorageQualifier.StorageType.UNIFORM);
	}

	private static boolean hasStorage(TypeQualifier qualifier, StorageQualifier.StorageType type) {
		if (qualifier == null) {
			return false;
		}

		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof StorageQualifier storage && storage.storageType == type) {
				return true;
			}
		}

		return false;
	}

	private static int arrayLength(ArraySpecifier specifier) {
		if (specifier == null) {
			return 0;
		}

		List<Expression> dimensions = specifier.getChildren();

		if (dimensions.size() != 1 || !(dimensions.get(0) instanceof LiteralExpression literal)) {
			throw new IllegalStateException("Only one-dimensional uniform arrays with a literal size are supported on Vulkan");
		}

		return (int) literal.getInteger();
	}
}
