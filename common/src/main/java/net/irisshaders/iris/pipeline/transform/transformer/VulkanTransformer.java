package net.irisshaders.iris.pipeline.transform.transformer;

import io.github.douira.glsl_transformer.ast.node.Identifier;
import io.github.douira.glsl_transformer.ast.node.declaration.FunctionParameter;
import io.github.douira.glsl_transformer.ast.node.type.qualifier.PrecisionQualifier;
import io.github.douira.glsl_transformer.ast.node.TranslationUnit;
import io.github.douira.glsl_transformer.ast.node.declaration.DeclarationMember;
import io.github.douira.glsl_transformer.ast.node.declaration.InterfaceBlockDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.PrecisionDeclaration;
import io.github.douira.glsl_transformer.ast.node.declaration.TypeAndInitDeclaration;
import io.github.douira.glsl_transformer.ast.node.expression.Expression;
import io.github.douira.glsl_transformer.ast.node.expression.LiteralExpression;
import io.github.douira.glsl_transformer.ast.node.expression.ReferenceExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.FunctionCallExpression;
import io.github.douira.glsl_transformer.ast.node.expression.unary.GroupingExpression;
import io.github.douira.glsl_transformer.ast.node.external_declaration.FunctionDefinition;
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
import net.irisshaders.iris.vulkan.VulkanStorage;
import net.irisshaders.iris.vulkan.VulkanUniformLayout;
import net.irisshaders.iris.vulkan.VulkanUnsupported;

import java.util.ArrayList;
import java.util.HashSet;
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
				tree.getRoot().indexBuildSession(root -> bindStorage(tree, root));
				tree.getRoot().indexBuildSession(root -> emulateShadowSamplers(t, tree, root));
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
				// GL ignores precision on parameters; glslang wants every declaration of a function to agree.
				root.process(root.nodeIndex.getStream(PrecisionQualifier.class)
					.filter(precision -> precision.getAncestor(FunctionParameter.class) != null), PrecisionQualifier::detachAndDelete);
				// Default precision ("precision highp float;") would give parameters declared after it a precision
				// that a prototype before it lacks (Bliss); it means nothing on desktop GPUs.
				// Packs written for old GLSL define built-ins themselves (Bliss: tanh). GL drivers let that pass,
				// glslang doesn't; the built-in computes the same.
				List<ExternalDeclaration> unwanted = tree.getChildren().stream()
					.filter(external -> external instanceof DeclarationExternalDeclaration declaration && declaration.getDeclaration() instanceof PrecisionDeclaration
						|| external instanceof FunctionDefinition definition && REDEFINED_BUILTINS.contains(definition.getFunctionPrototype().getName().getName()))
					.toList();
				tree.getChildren().removeAll(unwanted);
				unwanted.forEach(ExternalDeclaration::detachParent);

				// Identifiers that are keywords only in Vulkan GLSL (Bliss names a sampler2D parameter "sampler").
				for (String keyword : VULKAN_KEYWORDS) {
					if (root.identifierIndex.has(keyword)) {
						root.rename(keyword, "iris_vk_" + keyword);
					}
				}

				if (!layout.isEmpty()) {
					tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_DECLARATIONS, layout.toGlsl(ownGlobals(root, layout)));
				}

				root.rename("gl_VertexID", "gl_VertexIndex");
				root.rename("gl_InstanceID", "gl_InstanceIndex");

				if ((parameters.patch == Patch.VANILLA || parameters.patch == Patch.SODIUM)
					&& declaresSampler(root, "tex") && !root.identifierIndex.has("gtexture")) {
					// "tex" is the albedo sampler too (Complementary); GL Iris binds it under that name.
					root.rename("tex", "gtexture");
				}

				if (parameters.patch == Patch.VANILLA) {
					renameBindings(root, VANILLA_BINDINGS);
				} else if (parameters.patch == Patch.SODIUM) {
					renameBindings(root, SODIUM_BINDINGS);
				}
			});
		}
	}

	/** Built-in functions packs re-implement for old GLSL versions. */
	private static final Set<String> REDEFINED_BUILTINS = Set.of("tanh", "sinh", "cosh", "asinh", "acosh", "atanh",
		"fma", "round", "roundEven", "trunc", "inverse", "transpose", "determinant", "isnan", "isinf");

	/** Type names Vulkan GLSL adds (GL_KHR_vulkan_glsl) that are ordinary identifiers in GL GLSL. */
	private static final List<String> VULKAN_KEYWORDS = List.of("sampler", "samplerShadow", "subpassInput", "subpassInputMS");

	/** Block members this stage declares itself as a global (the uniform declarations were removed already). */
	private static Set<String> ownGlobals(Root root, VulkanUniformLayout layout) {
		Set<String> names = new HashSet<>();

		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (external.getDeclaration() instanceof TypeAndInitDeclaration declaration && !isUniform(declaration.getType().getTypeQualifier())) {
				for (DeclarationMember member : declaration.getMembers()) {
					if (layout.get(member.getName().getName()) != null) {
						names.add(member.getName().getName());
					}
				}
			}
		}

		return names;
	}

	/** Whether a uniform sampler (or other opaque uniform) with this name is declared at the top level. */
	private static boolean declaresSampler(Root root, String name) {
		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (external.getDeclaration() instanceof TypeAndInitDeclaration declaration
				&& isUniform(declaration.getType().getTypeQualifier())
				&& !(declaration.getType().getTypeSpecifier() instanceof BuiltinNumericTypeSpecifier)
				&& declaration.getMembers().stream().anyMatch(member -> member.getName().getName().equals(name))) {
				return true;
			}
		}

		return false;
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
	 * Storage images and SSBOs go into descriptor set 1 (see VulkanStorage), at the binding of their
	 * name (images) or GL binding point (SSBOs), the same in every program.
	 */
	private static void bindStorage(TranslationUnit tree, Root root) {
		splitImageDeclarations(tree, root);

		for (DeclarationExternalDeclaration external : root.nodeIndex.get(DeclarationExternalDeclaration.class)) {
			if (external.getDeclaration() instanceof TypeAndInitDeclaration declaration && isImage(declaration)) {
				if (declaration.getMembers().size() != 1 || declaration.getType().getTypeSpecifier().getArraySpecifier() != null
					|| declaration.getMembers().getFirst().getArraySpecifier() != null) {
					VulkanUnsupported.once("arrays of images");
					continue;
				}

				String name = declaration.getMembers().getFirst().getName().getName();
				int binding = VulkanStorage.imageBinding(name);

				if (binding < 0) {
					VulkanUnsupported.once("more than " + VulkanStorage.MAX_IMAGES + " storage images (" + name + ")");
					continue;
				}

				setStorageBinding(declaration.getType().getTypeQualifier(), binding);
			} else if (external.getDeclaration() instanceof InterfaceBlockDeclaration block
				&& hasStorage(block.getTypeQualifier(), StorageQualifier.StorageType.BUFFER)) {
				int index = Math.max(0, layoutValue(block.getTypeQualifier(), "binding"));
				int binding = VulkanStorage.bufferBinding(index);

				if (binding < 0) {
					VulkanUnsupported.once("shader storage buffer binding " + index);
					continue;
				}

				setStorageBinding(block.getTypeQualifier(), binding);
			}
		}
	}

	private static boolean isImage(TypeAndInitDeclaration declaration) {
		return isUniform(declaration.getType().getTypeQualifier())
			&& declaration.getType().getTypeSpecifier() instanceof BuiltinFixedTypeSpecifier fixed
			&& fixed.type.name().contains("IMAGE");
	}

	/** "uniform image3D a, b;" becomes one declaration per image, so each gets its own binding. */
	private static void splitImageDeclarations(TranslationUnit tree, Root root) {
		for (DeclarationExternalDeclaration external : List.copyOf(root.nodeIndex.get(DeclarationExternalDeclaration.class))) {
			if (!(external.getDeclaration() instanceof TypeAndInitDeclaration declaration) || !isImage(declaration)
				|| declaration.getMembers().size() < 2) {
				continue;
			}

			List<DeclarationMember> members = List.copyOf(declaration.getMembers());
			int index = tree.getChildren().indexOf(external);

			for (int i = members.size() - 1; i >= 1; i--) {
				DeclarationExternalDeclaration copy = external.cloneInto(root);
				List<DeclarationMember> copyMembers = List.copyOf(((TypeAndInitDeclaration) copy.getDeclaration()).getMembers());

				for (int j = 0; j < copyMembers.size(); j++) {
					if (j != i) {
						copyMembers.get(j).detachAndDelete();
					}
				}

				tree.getChildren().add(index + 1, copy);
			}

			for (int i = 1; i < members.size(); i++) {
				members.get(i).detachAndDelete();
			}
		}
	}

	/** layout(set = 1, binding = n), replacing a binding the pack gave. */
	private static void setStorageBinding(TypeQualifier qualifier, int binding) {
		LayoutQualifier layout = null;

		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier existing) {
				layout = existing;
				break;
			}
		}

		if (layout == null) {
			layout = new LayoutQualifier(Stream.empty());
			qualifier.getChildren().add(0, layout);
		}

		layout.getParts().removeIf(part -> part instanceof NamedLayoutQualifierPart named
			&& (named.getName().getName().equals("binding") || named.getName().getName().equals("set")));
		layout.getParts().add(0, new NamedLayoutQualifierPart(new Identifier("binding"), new LiteralExpression(Type.INT32, binding)));
		layout.getParts().add(0, new NamedLayoutQualifierPart(new Identifier("set"), new LiteralExpression(Type.INT32, VulkanStorage.SET)));
	}

	/** The literal value of layout(name = value), or -1. */
	private static int layoutValue(TypeQualifier qualifier, String name) {
		if (qualifier == null) {
			return -1;
		}

		for (TypeQualifierPart part : qualifier.getParts()) {
			if (part instanceof LayoutQualifier layout) {
				for (LayoutQualifierPart layoutPart : layout.getParts()) {
					if (layoutPart instanceof NamedLayoutQualifierPart named && named.getName().getName().equals(name)
						&& named.getExpression() instanceof LiteralExpression literal) {
						return (int) literal.getInteger();
					}
				}
			}
		}

		return -1;
	}

	/** Texture functions that compare against the reference on a sampler2DShadow, and their emulations below. */
	private static final Set<String> SHADOW_FUNCTIONS = Set.of("texture", "textureLod", "textureOffset", "textureLodOffset",
		"textureProj", "textureGrad", "textureGradOffset", "textureGather");
	private static final List<String> SHADOW_EMULATION = List.of(
		"""
			float iris_shadowCompare(sampler2D s, vec3 p, ivec2 offset) {
			    ivec2 size = textureSize(s, 0);
			    vec2 t = p.xy * vec2(size) - 0.5 + vec2(offset);
			    ivec2 i = ivec2(floor(t));
			    vec2 f = t - floor(t);
			    ivec2 m = size - 1;
			    vec4 d = vec4(texelFetch(s, clamp(i, ivec2(0), m), 0).r, texelFetch(s, clamp(i + ivec2(1, 0), ivec2(0), m), 0).r,
			        texelFetch(s, clamp(i + ivec2(0, 1), ivec2(0), m), 0).r, texelFetch(s, clamp(i + ivec2(1, 1), ivec2(0), m), 0).r);
			    vec4 lit = vec4(greaterThanEqual(d, vec4(p.z)));
			    return mix(mix(lit.x, lit.y, f.x), mix(lit.z, lit.w, f.x), f.y);
			}""",
		"float iris_shadow_texture(sampler2D s, vec3 p) { return iris_shadowCompare(s, p, ivec2(0)); }",
		"float iris_shadow_texture(sampler2D s, vec3 p, float bias) { return iris_shadowCompare(s, p, ivec2(0)); }",
		"float iris_shadow_textureLod(sampler2D s, vec3 p, float lod) { return iris_shadowCompare(s, p, ivec2(0)); }",
		"float iris_shadow_textureOffset(sampler2D s, vec3 p, ivec2 o) { return iris_shadowCompare(s, p, o); }",
		"float iris_shadow_textureOffset(sampler2D s, vec3 p, ivec2 o, float bias) { return iris_shadowCompare(s, p, o); }",
		"float iris_shadow_textureLodOffset(sampler2D s, vec3 p, float lod, ivec2 o) { return iris_shadowCompare(s, p, o); }",
		"float iris_shadow_textureProj(sampler2D s, vec4 p) { return iris_shadowCompare(s, p.xyz / p.w, ivec2(0)); }",
		"float iris_shadow_textureProj(sampler2D s, vec4 p, float bias) { return iris_shadowCompare(s, p.xyz / p.w, ivec2(0)); }",
		"float iris_shadow_textureGrad(sampler2D s, vec3 p, vec2 dx, vec2 dy) { return iris_shadowCompare(s, p, ivec2(0)); }",
		"float iris_shadow_textureGradOffset(sampler2D s, vec3 p, vec2 dx, vec2 dy, ivec2 o) { return iris_shadowCompare(s, p, o); }",
		"""
			vec4 iris_shadow_textureGather(sampler2D s, vec2 p, float ref) {
			    ivec2 size = textureSize(s, 0);
			    ivec2 i = ivec2(floor(p * vec2(size) - 0.5));
			    ivec2 m = size - 1;
			    vec4 d = vec4(texelFetch(s, clamp(i + ivec2(0, 1), ivec2(0), m), 0).r, texelFetch(s, clamp(i + ivec2(1, 1), ivec2(0), m), 0).r,
			        texelFetch(s, clamp(i + ivec2(1, 0), ivec2(0), m), 0).r, texelFetch(s, clamp(i, ivec2(0), m), 0).r);
			    return vec4(greaterThanEqual(d, vec4(ref)));
			}""");

	/**
	 * RenderPearl has no depth-compare samplers: sampler2DShadow becomes sampler2D and the comparing texture
	 * calls on it become functions doing the compare (GL_LEQUAL, the shadow map holds forward depth) on the
	 * four nearest texels, weighted like hardware PCF with linear filtering.
	 */
	private static void emulateShadowSamplers(ASTParser t, TranslationUnit tree, Root root) {
		Set<String> globals = new HashSet<>();
		Set<FunctionParameter> parameters = new HashSet<>();

		for (BuiltinFixedTypeSpecifier specifier : root.nodeIndex.get(BuiltinFixedTypeSpecifier.class)) {
			if (specifier.type != BuiltinFixedTypeSpecifier.BuiltinType.SAMPLER2DSHADOW) {
				continue;
			}

			FunctionParameter parameter = specifier.getAncestor(FunctionParameter.class);

			if (parameter != null) {
				parameters.add(parameter);
			} else if (specifier.getAncestor(TypeAndInitDeclaration.class) instanceof TypeAndInitDeclaration declaration) {
				declaration.getMembers().forEach(member -> globals.add(member.getName().getName()));
			}

			specifier.type = BuiltinFixedTypeSpecifier.BuiltinType.SAMPLER2D;
		}

		if (globals.isEmpty() && parameters.isEmpty()) {
			return;
		}

		List<FunctionCallExpression> calls = root.nodeIndex.getStream(FunctionCallExpression.class)
			.filter(call -> call.getFunctionName() != null && SHADOW_FUNCTIONS.contains(call.getFunctionName().getName())
				&& !call.getParameters().isEmpty() && isShadowSampler(call.getParameters().getFirst(), globals, parameters))
			.toList();

		for (FunctionCallExpression call : calls) {
			call.getFunctionName().setName("iris_shadow_" + call.getFunctionName().getName());
		}

		// Each one goes in front of the first function definition, so iris_shadowCompare is injected last.
		for (String function : SHADOW_EMULATION.reversed()) {
			tree.parseAndInjectNode(t, ASTInjectionPoint.BEFORE_FUNCTIONS, function);
		}
	}

	private static boolean isShadowSampler(Expression expression, Set<String> globals, Set<FunctionParameter> parameters) {
		while (expression instanceof GroupingExpression grouping) {
			expression = grouping.getOperand();
		}

		if (!(expression instanceof ReferenceExpression reference)) {
			return false;
		}

		String name = reference.getIdentifier().getName();
		FunctionDefinition function = expression.getAncestor(FunctionDefinition.class);

		if (function != null) {
			for (FunctionParameter parameter : function.getFunctionPrototype().getChildren()) {
				if (parameter.getName() != null && parameter.getName().getName().equals(name)) {
					return parameters.contains(parameter);
				}
			}
		}

		return globals.contains(name);
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
