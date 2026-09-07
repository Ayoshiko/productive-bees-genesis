package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 「哪些相邻离心机能处理这件产物」的跨 tick 掩码缓存。
 * <p>
 * 从 {@link ApiaryDirectEjectHandler} 拆出（SRP）：直连处理器只管搬运，
 * 可处理性判定的缓存与失效策略独立演进。
 * <p>
 * <b>为什么必须缓存：</b>{@code isValidInput} 内部要走配方查找，而离心机优先的 hold 判定
 * （{@link TileEntityMekApiary#shouldHoldForCentrifuge}）在 AE2 推送路径上是「每输出槽每 tick」
 * 级别的调用，直接查配方会把配方管理器顶成热点。
 * <p>
 * <b>失效条件：</b>目标列表引用变化（拓扑/侧面配置/朝向变了）或配方版本变化（{@code /reload}）。
 * 输入槽内容<b>不</b>参与失效——那是「有没有空间」，属于另一个问题。
 * <p>
 * <b>比较成本优化：</b>PB 的所有蜜脾共用同一个 {@code Item}，仅靠 {@code BEE_TYPE} 数据组件区分，
 * 因此 {@code isSameItemSameComponents} 的快速失败（比 Item）在混养蜂箱里几乎从不生效，
 * 64 条缓存每次都要做深比较。这里额外存一份组件 hash 先做 int 比较，
 * 把「64 次组件深比较」降为「64 次 int 比较 + 至多 1 次深比较」
 * （与 {@code Ae2OutputStateHolder} 的流体 key 缓存同一手法）。
 * <p>
 * 线程安全：仅服务端 tick 线程访问。
 */
final class ApiaryCentrifugeAcceptanceCache {

	/** 固定容量，防止高混养场景无界增长。 */
	private static final int CAPACITY = 64;

	private final ItemStack[] stacks = new ItemStack[CAPACITY];
	/** 与 stacks 对应的数据组件 hash，用于深比较前的 O(1) 预筛。 */
	private final int[] componentHashes = new int[CAPACITY];
	private final long[] targetMasks = new long[CAPACITY];
	private int size;

	/** 缓存对应的目标列表引用；重建即代表拓扑或侧面路由变化。 */
	private List<ApiaryDirectEjectTargets.Target> targetsRef;

	/** 缓存对应的配方版本；数据包重载会改变蜜脾可处理性。 */
	private long recipeVersion = Long.MIN_VALUE;

	/**
	 * 返回能处理该产物的相邻离心机位掩码（bit i 对应 targetList 的第 i 台）。
	 *
	 * @param stack      待判定产物
	 * @param targetList 当前直连目标列表（引用变化即触发缓存重建）
	 * @return 位掩码，0 表示没有任何相邻离心机能处理
	 */
	long maskFor(ItemStack stack, List<ApiaryDirectEjectTargets.Target> targetList) {
		if (targetList != targetsRef || recipeVersion != ProductiveBeesGenesis.RECIPE_VERSION.get()) {
			clear();
			targetsRef = targetList;
			recipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		}
		int componentHash = stack.getComponents().hashCode();
		for (int i = 0; i < size; i++) {
			// int 预筛：hash 不同必然不是同一产物，跳过昂贵的组件深比较
			if (componentHashes[i] != componentHash) continue;
			if (ItemStack.isSameItemSameComponents(stacks[i], stack)) return targetMasks[i];
		}
		long acceptedTargets = 0L;
		int targetCount = Math.min(targetList.size(), Long.SIZE);
		for (int i = 0; i < targetCount; i++) {
			try {
				if (targetList.get(i).centrifuge.productivebeesgenesis$isValidInput(stack)) {
					acceptedTargets |= 1L << i;
				}
			} catch (Exception | LinkageError e) {
				// 跨方块实体调用防御：离心机侧异常按"不可处理"降级，不阻断蜂箱 tick
				LogThrottle.warn("apiary_hold_input_check",
						"离心机可处理性判定异常，按不可处理降级: {}", stack.getItem(), e);
			}
		}
		if (size < CAPACITY) {
			stacks[size] = stack.copyWithCount(1);
			componentHashes[size] = componentHash;
			targetMasks[size] = acceptedTargets;
			size++;
		}
		return acceptedTargets;
	}

	/** 丢弃全部记忆（拓扑、路由或配方变化时调用）。 */
	void clear() {
		for (int i = 0; i < size; i++) {
			stacks[i] = null;
		}
		size = 0;
	}
}
