package com.ayoshiko.productivebeesgenesis.resource;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.*;

class JdteResourceMixinContractTest {
	private static ClassNode factory() throws IOException {
		try (var jar = new ZipFile("libs/jdte-0.6.1.0.jar")) {
			var entry = jar.getEntry(JdteResourceMixinContract.TARGET + ".class");
			assertNotNull(entry);
			try (var input = jar.getInputStream(entry)) {
				var node = new ClassNode();
				new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
				return node;
			}
		}
	}

	@Test void acceptsActualSupportedJarWithoutLoadingOptionalClasses() throws IOException {
		assertNull(JdteResourceMixinContract.incompatibility(factory()));
	}

	@Test void rejectsRemovedPendingOutputField() throws IOException {
		var node = factory();
		assertTrue(node.fields.removeIf(field -> field.name.equals("pendingOutput")));
		assertNotNull(JdteResourceMixinContract.incompatibility(node));
	}

	@Test void rejectsChangedFeeSignature() throws IOException {
		var node = factory();
		node.methods.stream().filter(method -> method.name.equals("getEffectiveTimeFluidCost"))
				.findFirst().orElseThrow().desc = "()J";
		assertNotNull(JdteResourceMixinContract.incompatibility(node));
	}

	@Test void rejectsMissingActiveOutputLayout() throws IOException {
		var node = factory();
		assertTrue(node.methods.removeIf(method -> method.name.equals("getActiveOutputSlots")));
		assertNotNull(JdteResourceMixinContract.incompatibility(node));
	}

	@Test void rejectsStaticProgressField() throws IOException {
		var node = factory();
		node.fields.stream().filter(field -> field.name.equals("progress"))
				.findFirst().orElseThrow().access |= Opcodes.ACC_STATIC;
		assertNotNull(JdteResourceMixinContract.incompatibility(node));
	}

	@Test void rejectsMissingOrDuplicateNaturalCompletion() throws IOException {
		var node = factory();
		var tick = node.methods.stream().filter(method -> method.name.equals("tickServer")).findFirst().orElseThrow();
		var completion = java.util.Arrays.stream(tick.instructions.toArray())
				.filter(instruction -> instruction instanceof MethodInsnNode call && call.name.equals("completeCycle"))
				.findFirst().orElseThrow();
		tick.instructions.remove(completion);
		assertNotNull(JdteResourceMixinContract.incompatibility(node));
		tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "completeCycle", "()V", false));
		tick.instructions.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, node.name, "completeCycle", "()V", false));
		assertNotNull(JdteResourceMixinContract.incompatibility(node));
	}
}
