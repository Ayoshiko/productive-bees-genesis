package com.ayoshiko.productivebeesgenesis.compat.sophisticated;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

class SophisticatedStorageMixinContractTest {

	@Test
	void absentTargetsOrChangedSupplierDisableBothBridges() {
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(null, cached()));
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple(), null));
		var cached = cached();
		assertNull(SophisticatedStorageMixinContract.incompatibility(simple(), cached));
		cached.fields.getFirst().desc = "Ljava/lang/Object;";
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple(), cached));
		cached = cached();
		cached.fields.getFirst().access |= Opcodes.ACC_STATIC;
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple(), cached));
	}

	@Test
	void changedInterfaceMethodOrExternalInsertDisablesBothBridges() {
		var simple = simple();
		simple.methods.getFirst().desc = "(Lnet/minecraft/world/item/ItemStack;)Lnet/minecraft/world/item/ItemStack;";
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple, cached()));
		simple = simple();
		simple.access &= ~Opcodes.ACC_INTERFACE;
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple, cached()));
		var cached = cached();
		cached.methods.getFirst().access |= Opcodes.ACC_STATIC;
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple(), cached));
	}

	@Test
	void additionalExternalSimulationPolicyDisablesTheShortcut() {
		var cached = cached();
		cached.methods.getFirst().instructions.insert(new InsnNode(Opcodes.NOP));
		assertNotNull(SophisticatedStorageMixinContract.incompatibility(simple(), cached));
	}

	private static ClassNode simple() {
		var node = new ClassNode();
		node.name = SophisticatedStorageMixinContract.SIMPLE_INSERTER;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		node.interfaces.add("net/neoforged/neoforge/items/IItemHandlerModifiable");
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "insertItem",
				"(Lnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", null, null));
		return node;
	}

	private static ClassNode cached() {
		var node = new ClassNode();
		node.name = SophisticatedStorageMixinContract.CACHED_INSERT;
		node.access = Opcodes.ACC_PUBLIC;
		node.interfaces.add("net/neoforged/neoforge/items/IItemHandlerModifiable");
		node.fields.add(new FieldNode(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL,
				"wrappedHandlerGetter", "Ljava/util/function/Supplier;", null, null));
		var method = new MethodNode(Opcodes.ACC_PUBLIC, "insertItem",
				"(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", null, null);
		var execute = new LabelNode();
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 3));
		method.instructions.add(new JumpInsnNode(Opcodes.IFEQ, execute));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
		method.instructions.add(new FieldInsnNode(Opcodes.GETFIELD, node.name,
				"wrappedHandlerGetter", "Ljava/util/function/Supplier;"));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE, "java/util/function/Supplier",
				"get", "()Ljava/lang/Object;", true));
		method.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "net/neoforged/neoforge/items/IItemHandlerModifiable"));
		method.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
		method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 2));
		method.instructions.add(new InsnNode(Opcodes.ICONST_1));
		method.instructions.add(new MethodInsnNode(Opcodes.INVOKEINTERFACE,
				"net/neoforged/neoforge/items/IItemHandlerModifiable", "insertItem",
				"(ILnet/minecraft/world/item/ItemStack;Z)Lnet/minecraft/world/item/ItemStack;", true));
		method.instructions.add(new InsnNode(Opcodes.ARETURN));
		method.instructions.add(execute);
		node.methods.add(method);
		return node;
	}
}
