package com.ayoshiko.productivebeesgenesis.mek.ae2;

import static org.mockito.Mockito.*;

import appeng.api.crafting.IPatternDetails;
import appeng.api.implementations.blockentities.ICraftingMachine;
import appeng.api.networking.IManagedGridNode;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.GenericStack;
import appeng.api.stacks.KeyCounter;
import appeng.helpers.patternprovider.PatternProviderLogic;
import appeng.helpers.patternprovider.PatternProviderLogicHost;
import appeng.helpers.patternprovider.PatternProviderTarget;
import java.lang.reflect.Field;
import java.util.EnumSet;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 仅隔离世界能力查找与样板注册；提交、busy、暂存和排空均走真实 AE2 代码。 */
final class ActualPatternProviderFixture {
	final PatternProviderLogic logic;
	private final List<IPatternDetails> patterns;
	private final List<GenericStack> pending;

	@SuppressWarnings("unchecked")
	ActualPatternProviderFixture(PatternProviderTarget target) throws ReflectiveOperationException {
		var node = mock(IManagedGridNode.class, RETURNS_SELF);
		when(node.isActive()).thenReturn(true);
		var host = mock(PatternProviderLogicHost.class);
		when(host.getTargets()).thenAnswer(call -> EnumSet.of(Direction.NORTH));
		var blockEntity = mock(BlockEntity.class);
		when(blockEntity.getBlockPos()).thenReturn(BlockPos.ZERO);
		when(host.getBlockEntity()).thenReturn(blockEntity);
		logic = new PatternProviderLogic(node, host);
		patterns = (List<IPatternDetails>) field("patterns").get(logic);
		pending = (List<GenericStack>) field("sendList").get(logic);
		var cacheType = Class.forName("appeng.helpers.patternprovider.PatternProviderTargetCache");
		var cache = mock(cacheType, call -> call.getMethod().getName().equals("find")
				? target : RETURNS_DEFAULTS.answer(call));
		((Object[]) field("targetCaches").get(logic))[Direction.NORTH.ordinal()] = cache;
	}

	boolean push(IPatternDetails pattern, KeyCounter[] inputs) {
		// 调度器传入的缩放样板已被选定；本夹具不测试供应器的样板编解码。
		patterns.clear();
		patterns.add(pattern);
		try (var lookup = mockStatic(ICraftingMachine.class)) {
			return logic.pushPattern(pattern, inputs);
		}
	}

	long pending(AEKey key) {
		return pending.stream().filter(stack -> stack.what().equals(key))
				.mapToLong(GenericStack::amount).sum();
	}

	boolean drain() throws ReflectiveOperationException {
		var method = PatternProviderLogic.class.getDeclaredMethod("sendStacksOut");
		method.setAccessible(true);
		return (boolean) method.invoke(logic);
	}

	private static Field field(String name) throws ReflectiveOperationException {
		var field = PatternProviderLogic.class.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}
}
