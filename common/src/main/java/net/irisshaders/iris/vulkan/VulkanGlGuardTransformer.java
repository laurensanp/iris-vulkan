package net.irisshaders.iris.vulkan;

import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

/**
 * Makes every static method of RenderPearl's GlStateManager return at once (with 0/false/null) while
 * the game runs on Vulkan. GlStateManager is the GL state wrapper Iris calls all over; on Vulkan
 * there is no GL context and LWJGL aborts the JVM on such calls. What Iris needs on Vulkan is
 * ported in the leaves (IrisRenderSystem, GlFramebuffer, ...); anything still landing here is
 * logged once per method by {@code VulkanGlGuard}.
 */
public final class VulkanGlGuardTransformer {
	public static final String TARGET = "com.mojang.renderpearl.backend.opengl.GlStateManager";
	private static final String GUARD_OWNER = "net/irisshaders/iris/vulkan/VulkanGlGuard";
	private static final String GUARD_METHOD = "suppress";

	private VulkanGlGuardTransformer() {
	}

	public static void transform(ClassNode target) {
		for (MethodNode method : target.methods) {
			// Only the public API: private and synthetic helpers (lambdas) also serve the static initializer.
			if ((method.access & Opcodes.ACC_STATIC) == 0 || (method.access & Opcodes.ACC_PUBLIC) == 0
				|| (method.access & (Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE | Opcodes.ACC_SYNTHETIC)) != 0
				|| method.name.startsWith("<") || isGuarded(method)) {
				continue;
			}

			LabelNode proceed = new LabelNode();
			InsnList guard = new InsnList();
			guard.add(new LdcInsnNode(method.name));
			guard.add(new MethodInsnNode(Opcodes.INVOKESTATIC, GUARD_OWNER, GUARD_METHOD, "(Ljava/lang/String;)Z", false));
			guard.add(new JumpInsnNode(Opcodes.IFEQ, proceed));
			addDefaultReturn(guard, Type.getReturnType(method.desc));
			guard.add(proceed);
			guard.add(new FrameNode(Opcodes.F_SAME, 0, null, 0, null));
			method.instructions.insert(guard);
		}
	}

	/** postApply runs once per mixin on the class: guard each method only once. */
	private static boolean isGuarded(MethodNode method) {
		for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
			if (insn.getOpcode() < 0) {
				continue;
			}

			if (insn instanceof LdcInsnNode) {
				AbstractInsnNode next = insn.getNext();
				return next instanceof MethodInsnNode call && call.owner.equals(GUARD_OWNER) && call.name.equals(GUARD_METHOD);
			}

			return false;
		}

		return false;
	}

	private static void addDefaultReturn(InsnList insns, Type type) {
		switch (type.getSort()) {
			case Type.VOID -> insns.add(new InsnNode(Opcodes.RETURN));
			case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT -> {
				insns.add(new InsnNode(Opcodes.ICONST_0));
				insns.add(new InsnNode(Opcodes.IRETURN));
			}
			case Type.LONG -> {
				insns.add(new InsnNode(Opcodes.LCONST_0));
				insns.add(new InsnNode(Opcodes.LRETURN));
			}
			case Type.FLOAT -> {
				insns.add(new InsnNode(Opcodes.FCONST_0));
				insns.add(new InsnNode(Opcodes.FRETURN));
			}
			case Type.DOUBLE -> {
				insns.add(new InsnNode(Opcodes.DCONST_0));
				insns.add(new InsnNode(Opcodes.DRETURN));
			}
			default -> {
				insns.add(new InsnNode(Opcodes.ACONST_NULL));
				insns.add(new InsnNode(Opcodes.ARETURN));
			}
		}
	}
}
