package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.helpers.patternprovider.PatternProviderTarget;
import com.ayoshiko.productivebeesgenesis.init.ModBlockEntities;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.inventory.TieredInputSlot;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import mekanism.api.RelativeSide;
import mekanism.common.inventory.slot.BasicInventorySlot;
import mekanism.common.inventory.slot.InputInventorySlot;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.component.config.ConfigInfo;
import mekanism.common.tile.component.config.DataType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 隔离服务器内、真实构造本模组机器的 AE2 外部插入目标（经真实 {@code wrapMeStorage} 与供应器 Mixin），
 * 用于在服务器线程上量测「本模组离心机共享 AIMD 自适应预算」的可持续每刻推送次数。
 *
 * <p>放在 {@code mek.ae2} 包内是因为 {@link CentrifugeExternalAeStorage#getOrCreate} 是包级可见；
 * 本类属于 dispatchProbe 源集，仅基准运行时加载。工作集取极大值以隔离 AIMD（让插入返回 0 只源于
 * 预算耗尽而非缓冲填满），每刻测量前清空输入槽避免缓冲累积。</p>
 */
public final class CentrifugeDispatchProbe {

	private final AEItemKey iron = AEItemKey.of(Items.RAW_IRON);
	private final List<InputInventorySlot> inputs = new ArrayList<>();
	private final PatternProviderTarget target;

	public CentrifugeDispatchProbe() throws Exception {
		var sideHost = ModBlockEntities.MEK_CENTRIFUGE.get().create(
				BlockPos.ZERO, ModBlocks.MEK_CENTRIFUGE.get().defaultBlockState());
		if (sideHost == null) {
			throw new IllegalStateException("Cannot create MEK centrifuge block entity");
		}
		ConfigInfo config = sideHost.getConfig().getConfig(TransmissionType.ITEM);
		config.setDataType(DataType.INPUT, RelativeSide.TOP);
		BasicInventorySlot output = BasicInventorySlot.at(null, 0, 0);
		Ae2OutputStateHolder holder = new Ae2OutputStateHolder();
		for (int i = 0; i < 17; i++) {
			InputInventorySlot slot = InputInventorySlot.at(stack -> stack.is(Items.RAW_IRON), null, 0, 0);
			((TieredInputSlot) slot).productivebeesgenesis$setInputStackMultiplier(() -> 278_528);
			((TieredInputSlot) slot).productivebeesgenesis$markInputSlot();
			// 外部插入直接放行到槽位真实容量（无工作集节流）；每刻清空隔离缓冲，使成本只反映一次外部插入。
			inputs.add(slot);
		}
		IAe2OutputHostBase host = (IAe2OutputHostBase) Proxy.newProxyInstance(
				IAe2OutputHostBase.class.getClassLoader(),
				new Class<?>[]{IAe2OutputHostBase.class, IMekCentrifugeTile.class}, (proxy, method, args) ->
					switch (method.getName()) {
						case "productivebeesgenesis$getAe2StateHolder" -> holder;
						case "productivebeesgenesis$getInputSlotCount" -> inputs.size();
						case "productivebeesgenesis$getInputSlot" -> inputs.get((int) args[0]);
						case "productivebeesgenesis$isValidInput" -> ((ItemStack) args[0]).is(Items.RAW_IRON);
						case "processes" -> 1;
						case "primaryOutputSlot" -> output;
						case "secondaryOutputSlot", "tertiaryOutputSlot", "productivebeesgenesis$onAe2PushComplete",
								"productivebeesgenesis$markInputSortingNeeded" -> null;
						case "productivebeesgenesis$outputContentsVersion" -> (long) output.getCount();
						default -> throw new AssertionError("Unexpected host call: " + method.getName());
					});
		MEStorage storage = CentrifugeExternalAeStorage.getOrCreate(host, (IMekCentrifugeTile) host, sideHost, Direction.UP);
		Method wrap = PatternProviderTarget.class.getDeclaredMethod("wrapMeStorage", MEStorage.class, IActionSource.class);
		wrap.setAccessible(true);
		target = (PatternProviderTarget) wrap.invoke(null, storage, IActionSource.empty());
	}

	/**
	 * 模拟逐份发配：本刻内一直推送 1 份，最多 {@code cap} 次或直到本机 AIMD 预算耗尽（插入返回 0）。
	 * 返回本刻被接收的推送次数。测量前清空输入槽以隔离缓冲，使成本只反映「一次外部插入」的真实开销。
	 */
	public int floodPerCopyOneTick(int cap) {
		return floodPerCopyOneTick(cap, 0L);
	}

	/**
	 * 逐份发配洪水，并在每次被接收的推送后自旋 {@code perPushNanos} 纳秒，<b>模拟各 CPU「每次样板推送」
	 * 在合成主线程上的机器开销</b>（findApi/批执行等）。这样洪水的耗时会计入服务器真实 tick 时间、被
	 * {@link com.ayoshiko.productivebeesgenesis.mek.ServerTickTimeMonitor} 采到 → 驱动 AIMD 按 MSPT 收敛，
	 * 从而在隔离服务器里复现并检验「预算是否稳定收敛在上限、不再大幅震荡」。返回本刻被接收的推送次数。
	 */
	public int floodPerCopyOneTick(int cap, long perPushNanos) {
		for (InputInventorySlot slot : inputs) {
			slot.setEmpty();
		}
		int accepted = 0;
		while (accepted < cap && target.insert(iron, 1L, Actionable.MODULATE) > 0L) {
			accepted++;
			if (perPushNanos > 0L) {
				long deadline = System.nanoTime() + perPushNanos;
				while (System.nanoTime() < deadline) {
					// 自旋占用主线程，模拟该 CPU 每推送的机器开销
				}
			}
		}
		return accepted;
	}

	/** 洪水结束后，同刻重复提交仍须拒收，模拟容量则保持真实。 */
	public boolean exhaustedWithoutLyingAboutCapacity() {
		return target.insert(iron, 1L, Actionable.MODULATE) == 0L
				&& target.insert(iron, 10_000_000L, Actionable.SIMULATE) == 10_000_000L;
	}

	/** 当前本模组离心机共享 AIMD 预算（本包可见），供隔离服务器基准记录预算随刻的收敛/震荡曲线。 */
	public int currentBudget() {
		return CentrifugeDispatchScope.budgetForTest();
	}
}
