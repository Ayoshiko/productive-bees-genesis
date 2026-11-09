package com.ayoshiko.productivebeesgenesis.mixin.iris;

import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.MethodNode;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IrisConfigPluginTest {
	private static final String SKIP_LIST = "net/irisshaders/iris/compat/SkipList";
	private static final String SHADER_INTERFACE = "net/irisshaders/iris/mixinterface/ShaderInstanceInterface";
	private static final String SKIP_DESCRIPTOR = "Ljava/lang/invoke/MethodHandle;";

	@Test
	void acceptsPublicIrisShaderSkipContract() {
		assertTrue(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface()));
	}

	@Test
	void rejectsMissingClassesFromAlternativeIrisProviders() {
		assertFalse(IrisConfigPlugin.supportsShaderSkip(null, shaderInterface()));
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), null));
		assertFalse(IrisConfigPlugin.supportsShaderSkip(null, null));
	}

	@Test
	void rejectsMissingOrInaccessibleNoneConstant() {
		ClassNode skipList = skipList();
		skipList.fields.clear();
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList, shaderInterface()));

		skipList = skipList();
		skipList.fields.getFirst().access = Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC;
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList, shaderInterface()));

		skipList = skipList();
		skipList.fields.getFirst().access = Opcodes.ACC_PUBLIC;
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList, shaderInterface()));
	}

	@Test
	void rejectsIncompatibleConstantTypeOrName() {
		ClassNode skipList = skipList();
		skipList.fields.getFirst().desc = "Ljava/lang/Object;";
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList, shaderInterface()));

		skipList = skipList();
		skipList.fields.getFirst().name = "ALWAYS";
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList, shaderInterface()));
	}

	@Test
	void rejectsMissingOrIncompatibleSetter() {
		ClassNode shaderInterface = shaderInterface();
		shaderInterface.methods.clear();
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface));

		shaderInterface = shaderInterface();
		shaderInterface.methods.getFirst().desc = "(Z)V";
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface));

		shaderInterface = shaderInterface();
		shaderInterface.methods.getFirst().access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC;
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface));

		shaderInterface = shaderInterface();
		shaderInterface.methods.getFirst().access = Opcodes.ACC_PRIVATE;
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface));
	}

	@Test
	void rejectsNonPublicTypesAndNonInterfaceSetterOwner() {
		ClassNode skipList = skipList();
		skipList.access = Opcodes.ACC_ENUM;
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList, shaderInterface()));

		ClassNode shaderInterface = shaderInterface();
		shaderInterface.access = Opcodes.ACC_PUBLIC;
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface));

		shaderInterface = shaderInterface();
		shaderInterface.name = "other/ShaderInstanceInterface";
		assertFalse(IrisConfigPlugin.supportsShaderSkip(skipList(), shaderInterface));
	}

	private static ClassNode skipList() {
		ClassNode node = new ClassNode();
		node.name = SKIP_LIST;
		node.access = Opcodes.ACC_PUBLIC;
		node.fields.add(new FieldNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL,
				"NONE", SKIP_DESCRIPTOR, null, null));
		return node;
	}

	private static ClassNode shaderInterface() {
		ClassNode node = new ClassNode();
		node.name = SHADER_INTERFACE;
		node.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT;
		node.methods.add(new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT,
				"setShouldSkip", "(" + SKIP_DESCRIPTOR + ")V", null, null));
		return node;
	}
}
