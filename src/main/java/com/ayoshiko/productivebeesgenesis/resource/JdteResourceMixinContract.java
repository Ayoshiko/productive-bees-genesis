package com.ayoshiko.productivebeesgenesis.resource;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

/** JDTE 过渡桥的二进制入口检查；只读字节码，不触发可选依赖类初始化。 */
public final class JdteResourceMixinContract {
	static final String TARGET = "com/jdte/common/blockentities/BioFactoryBE";
	private static final Map<String, String> FIELDS = Map.of(
			"cachedRecipe", "Lcom/jdte/common/recipes/BioFactoryRecipe;",
			"cachedBee", "Lnet/minecraft/world/entity/animal/Bee;",
			"cachedSpecimen", "Lnet/minecraft/world/item/ItemStack;",
			"progress", "I",
			"clientSyncPending", "Z",
			"pendingOutput", "Lcom/jdte/common/blockentities/BioFactoryPendingOutput;");
	private static final Set<String> METHODS = Set.of(
			"resolveRecipe()Z", "completeCycle()V", "clearRecipeCache()V",
			"inputsMatchCache()Z", "canCachedBeeOperate()Z", "getCurrentProcessTicks()I",
			"getEffectiveCycleEnergyCost(Lcom/jdte/common/recipes/BioFactoryRecipe;)I",
			"getEffectiveTimeFluidCost()I", "getEffectiveLifeFluidCost()I",
			"getProductivityMultiplier()D", "getActiveOutputSlots()I",
			"getUpgradeHandler()Lcom/jdte/common/upgrades/BioFactoryUpgradeItemStackHandler;",
			"getLifeFluidTank()Lcom/jdte/common/upgrades/JDTEFluidTank;",
			"getTimeFluidTank()Lcom/jdte/common/upgrades/JDTEFluidTank;",
			"getMachineHandler()Lnet/neoforged/neoforge/items/ItemStackHandler;",
			"canRun()Z");

	private JdteResourceMixinContract() {}

	/** 版本门之外再核对实际字段、方法和自然 tick 的完成入口。 */
	public static boolean supported() {
		if (publicApiSupported()) return false;
		try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(TARGET + ".class")) {
			if (input == null) return disabled("BioFactoryBE class is missing");
			var node = new ClassNode();
			new ClassReader(input).accept(node, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			var problem = incompatibility(node);
			return problem == null || disabled(problem);
		} catch (IOException | RuntimeException failure) {
			return disabled(failure.toString());
		}
	}

	/** 正式 API 的早期标记，只读字节码；旧过渡 Mixin 与公共入口必须互斥。 */
	public static boolean publicApiSupported() {
		try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(
				"com/jdte/api/BioFactoryResourceCycleEvent.class")) {
			if (input == null) return false;
			var node = new ClassNode();
			new ClassReader(input).accept(node, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
			return node.fields.stream().anyMatch(field -> field.name.equals("API_VERSION") && field.desc.equals("I")
					&& (field.access & (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL))
					== (Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL)
                    && (Integer.valueOf(1).equals(field.value) || Integer.valueOf(2).equals(field.value)
                    || Integer.valueOf(3).equals(field.value)));
		} catch (IOException | RuntimeException invalid) { return false; }
	}

	// 包内测试使用真实 JAR 字节码及损坏入口，不能用类加载成功替代注入合同验证。
	static String incompatibility(ClassNode node) {
		if (!TARGET.equals(node.name)) return "Unexpected target class";
		for (var expected : FIELDS.entrySet()) {
			if (node.fields.stream().noneMatch(field -> field.name.equals(expected.getKey())
					&& field.desc.equals(expected.getValue()) && (field.access & Opcodes.ACC_STATIC) == 0)) {
				return "Missing instance field " + expected.getKey() + ":" + expected.getValue();
			}
		}
		for (var signature : METHODS) {
			if (node.methods.stream().noneMatch(method -> signature.equals(method.name + method.desc)
					&& (method.access & (Opcodes.ACC_STATIC | Opcodes.ACC_ABSTRACT | Opcodes.ACC_NATIVE)) == 0)) {
				return "Missing instance method " + signature;
			}
		}
		var tick = node.methods.stream().filter(method -> "tickServer".equals(method.name)
				&& "()V".equals(method.desc) && (method.access & Opcodes.ACC_STATIC) == 0).findFirst().orElse(null);
		if (tick == null) return "Missing tickServer()V";
		int completions = 0;
		int resolutions = 0;
		for (var instruction : tick.instructions) {
			if (instruction instanceof MethodInsnNode call && TARGET.equals(call.owner)) {
				if ("completeCycle".equals(call.name) && "()V".equals(call.desc)) completions++;
				if ("resolveRecipe".equals(call.name) && "()Z".equals(call.desc)) resolutions++;
			}
		}
		return completions == 1 && resolutions == 1 ? null : "Changed tick completion or resolution boundary";
	}

	private static boolean disabled(String reason) {
		System.err.println("[ProductiveBeesGenesis] JDTE resource bridge disabled: " + reason);
		return false;
	}
}
