package com.ayoshiko.productivebeesgenesis.compat.sophisticated;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/** 整体模拟的两个桥必须同时满足二进制合同；只读字节码，不提前加载可选类。 */
public final class SophisticatedStorageMixinContract {

	static final String SIMPLE_INSERTER = "net/p3pp3rf1y/sophisticatedcore/inventory/IItemHandlerSimpleInserter";
	static final String CACHED_INSERT = "net/p3pp3rf1y/sophisticatedcore/inventory/CachedFailedInsertInventoryHandler";
	private static final String MODIFIABLE_HANDLER = "net/neoforged/neoforge/items/IItemHandlerModifiable";
	private static final String ITEM_STACK = "Lnet/minecraft/world/item/ItemStack;";

	private SophisticatedStorageMixinContract() {
	}

	private static final class Holder {
		private static final boolean SUPPORTED = inspect();
	}

	public static boolean supported() {
		return Holder.SUPPORTED;
	}

	private static boolean inspect() {
		try {
			ClassNode simple = readClass(SIMPLE_INSERTER);
			ClassNode cached = readClass(CACHED_INSERT);
			String problem = incompatibility(simple, cached);
			if (problem == null) return true;
			return disabled(problem);
		} catch (IOException | RuntimeException | LinkageError failure) {
			return disabled(failure.toString());
		}
	}

	static String incompatibility(ClassNode simple, ClassNode cached) {
		if (simple == null || cached == null) return "Missing simulation bridge target";
		if (!SIMPLE_INSERTER.equals(simple.name) || (simple.access & Opcodes.ACC_INTERFACE) == 0
				|| !simple.interfaces.contains(MODIFIABLE_HANDLER)) {
			return "Changed simple inserter interface";
		}
		if (simple.methods.stream().noneMatch(method -> method.name.equals("insertItem")
				&& method.desc.equals("(" + ITEM_STACK + "Z)" + ITEM_STACK)
				&& (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_STATIC))
				== (Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT))) {
			return "Missing public whole-inventory simulation method";
		}
		if (!CACHED_INSERT.equals(cached.name) || (cached.access & (Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT)) != 0
				|| !cached.interfaces.contains(MODIFIABLE_HANDLER)) {
			return "Changed external insert wrapper";
		}
		if (cached.fields.stream().noneMatch(field -> field.name.equals("wrappedHandlerGetter")
				&& field.desc.equals("Ljava/util/function/Supplier;")
				&& (field.access & (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL | Opcodes.ACC_STATIC))
				== (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL))) {
			return "Missing final dynamic inventory supplier";
		}
		MethodNode externalInsert = cached.methods.stream().filter(method -> method.name.equals("insertItem")
				&& method.desc.equals("(I" + ITEM_STACK + "Z)" + ITEM_STACK)
				&& (method.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE))
				== Opcodes.ACC_PUBLIC).findFirst().orElse(null);
		if (externalInsert == null) {
			return "Missing public external insert method";
		}
		if (!forwardsSimulation(externalInsert)) return "Changed external simulation policy";
		return null;
	}

	private static boolean forwardsSimulation(MethodNode method) {
		if (!method.tryCatchBlocks.isEmpty()) return false;
		var prefix = new ArrayList<AbstractInsnNode>(11);
		for (AbstractInsnNode instruction : method.instructions) {
			if (instruction.getOpcode() < 0) continue;
			prefix.add(instruction);
			if (instruction.getOpcode() == Opcodes.ARETURN || prefix.size() > 11) break;
		}
		return prefix.size() == 11
				&& variable(prefix.get(0), Opcodes.ILOAD, 3)
				&& prefix.get(1) instanceof JumpInsnNode branch && branch.getOpcode() == Opcodes.IFEQ
				&& variable(prefix.get(2), Opcodes.ALOAD, 0)
				&& prefix.get(3) instanceof FieldInsnNode field && field.getOpcode() == Opcodes.GETFIELD
				&& field.owner.equals(CACHED_INSERT) && field.name.equals("wrappedHandlerGetter")
				&& field.desc.equals("Ljava/util/function/Supplier;")
				&& call(prefix.get(4), "java/util/function/Supplier", "get", "()Ljava/lang/Object;")
				&& prefix.get(5) instanceof TypeInsnNode cast && cast.getOpcode() == Opcodes.CHECKCAST
				&& cast.desc.equals(MODIFIABLE_HANDLER)
				&& variable(prefix.get(6), Opcodes.ILOAD, 1)
				&& variable(prefix.get(7), Opcodes.ALOAD, 2)
				&& prefix.get(8).getOpcode() == Opcodes.ICONST_1
				&& call(prefix.get(9), MODIFIABLE_HANDLER, "insertItem", "(I" + ITEM_STACK + "Z)" + ITEM_STACK)
				&& prefix.get(10).getOpcode() == Opcodes.ARETURN;
	}

	private static boolean variable(AbstractInsnNode instruction, int opcode, int index) {
		return instruction instanceof VarInsnNode variable && variable.getOpcode() == opcode && variable.var == index;
	}

	private static boolean call(AbstractInsnNode instruction, String owner, String name, String descriptor) {
		return instruction instanceof MethodInsnNode call && call.getOpcode() == Opcodes.INVOKEINTERFACE
				&& call.owner.equals(owner) && call.name.equals(name) && call.desc.equals(descriptor);
	}

	private static ClassNode readClass(String name) throws IOException {
		try (InputStream input = openClass(name + ".class")) {
			if (input == null) return null;
			ClassNode node = new ClassNode();
			new ClassReader(input).accept(node,
					ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node;
		}
	}

	private static InputStream openClass(String resource) {
		ClassLoader context = Thread.currentThread().getContextClassLoader();
		if (context != null) {
			InputStream input = context.getResourceAsStream(resource);
			if (input != null) return input;
		}
		return SophisticatedStorageMixinContract.class.getClassLoader().getResourceAsStream(resource);
	}

	private static boolean disabled(String reason) {
		System.err.println("[ProductiveBeesGenesis] Sophisticated whole-inventory simulation disabled: " + reason);
		return false;
	}
}
