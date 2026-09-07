package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.BoundedBooleanMemo;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import mekanism.common.recipe.lookup.cache.InputRecipeCache;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * Per-host bounded cache for Mekanism SMELTING input queries.
 * <p>
 * <b>身份键分两档，这是本类的核心设计</b>：
 * <ul>
 *   <li><b>无组件补丁</b>（矿石、原矿、锭、粉等绝大多数熔炼原料）→ 键为
 *       {@link Item} 引用。同一 Item 且无补丁即等于「默认组件」，是唯一身份，
 *       因此按 Item 记忆与按完整键记忆的结果完全等价；</li>
 *   <li><b>带组件补丁</b>（玩家重命名、附魔书等）→ 退化为完整 {@link AEItemKey}，
 *       因为 Mekanism 的 SMELTING 查找是 {@code ComponentSensitiveInputCache}，
 *       组件会改变判定结果。</li>
 * </ul>
 * <b>为什么不能统一用 AEItemKey</b>：{@code AEItemKey.equals} 会走
 * {@code ItemStack.isSameItemSameComponents}（装有 geckolib 时还被其 mixin 包裹）。
 * spark BkTP3d9oSc 中 {@code AEItemKey.equals} 自身 self 时间 1416ms（2.36%），
 * 为全服第 5 热方法；{@code CombFuzzyMatcher} / {@code Ae2CombProcessableCache} /
 * {@code InputValidationCache} 早已改用 Item 身份键，本类是最后一处遗漏。
 * 蜜脾（带 bee_type 补丁）在 {@link Ae2InputCandidatePolicy#classify} 中已被
 * {@code isCombItem} 提前拦截，不会落到 SMELTING 分支，故第二档流量极低。
 * <p>
 * <b>Mekanism 输入缓存句柄按配方版本缓存</b>：{@code MekanismRecipeType.SMELTING.getInputCache()}
 * 每次调用都要穿一层 {@code DeferredHolder.value()}（注册表解析 + 非空校验）。
 * spark BkTP3d9oSc 中它自身 self 时间 320ms（0.53%），几乎与真正的配方查询等量。
 * 句柄在一次配方重载周期内恒定，因此随 {@link ProductiveBeesGenesis#RECIPE_VERSION} 一起缓存。
 * <p>
 * <b>线程安全</b>：查询只在服务端 tick 线程发生，因此不加锁；
 * AE2 网格回调的 {@link #clear()} 只投递失效请求（见 {@link BoundedBooleanMemo#requestClear}），
 * 真正清表推迟到 tick 线程下一次查询，从而彻底移除热路径上的 monitor 开销。
 * <p>
 * <b>异常语义</b>：查询失败按「拒绝候选」处理并限流告警 ——
 * 破损的可选配方集成绝不能让离心机拉进不安全的物品。
 */
final class Ae2SmeltingInputCache {

	/** 无组件补丁档（按 Item）单代上限；覆盖大型整合包的熔炼原料种类。 */
	static final int MAX_ENTRIES = 1_024;

	/** 带组件补丁档（按完整键）单代上限；正常整合包里这一档几乎为空。 */
	static final int MAX_COMPONENT_ENTRIES = 128;

	/** 默认组件物品的判定结果：按 Item 引用记忆，零组件哈希开销。 */
	private final BoundedBooleanMemo<Item> plainItems = new BoundedBooleanMemo<>(MAX_ENTRIES);

	/** 带组件补丁物品的判定结果：保留完整键语义。 */
	private final BoundedBooleanMemo<AEItemKey> componentKeys =
			new BoundedBooleanMemo<>(MAX_COMPONENT_ENTRIES);

	private long observedRecipeVersion = Long.MIN_VALUE;

	/** 与 {@link #observedRecipeVersion} 同周期的 Mekanism SMELTING 输入缓存句柄。 */
	private InputRecipeCache.SingleItem<ItemStackToItemStackRecipe> inputCache;

	/**
	 * 检查 Mekanism 是否存在匹配该 AE 键的 SMELTING 配方。
	 * <p>
	 * AE2 返回的只读栈不会被本类修改或持有。
	 */
	boolean contains(Level level, AEItemKey key) {
		if (level == null || key == null) return false;
		refreshRecipeVersion();
		// getReadOnlyStack 不拷贝，仅用于读取组件补丁与 Item
		ItemStack input = key.getReadOnlyStack();
		if (input.getComponentsPatch().isEmpty()) {
			Item item = input.getItem();
			int state = plainItems.state(item);
			if (state != BoundedBooleanMemo.STATE_UNKNOWN) {
				return state == BoundedBooleanMemo.STATE_TRUE;
			}
			return plainItems.remember(item, query(level, input, key));
		}
		int state = componentKeys.state(key);
		if (state != BoundedBooleanMemo.STATE_UNKNOWN) {
			return state == BoundedBooleanMemo.STATE_TRUE;
		}
		return componentKeys.remember(key, query(level, input, key));
	}

	/** 清空所有缓存结果，例如 AE2 网格拓扑变化后。可从任意线程调用。 */
	void clear() {
		plainItems.requestClear();
		componentKeys.requestClear();
	}

	/** 当前缓存条目数，供诊断与测试使用。 */
	int size() {
		return plainItems.size() + componentKeys.size();
	}

	private boolean query(Level level, ItemStack input, AEItemKey key) {
		// 句柄解析失败（环境损坏）时按拒绝处理，与查询异常同一语义
		if (inputCache == null) return false;
		try {
			return inputCache.containsInput(level, input);
		} catch (LinkageError | RuntimeException error) {
			LogThrottle.warn("ae2_smelting_input_cache",
					"AE2 SMELTING 配方查询异常，拒绝本次候选 key={}: {}", key, error.toString());
			return false;
		}
	}

	/**
	 * 配方重载即整体失效，并重新解析 Mekanism 输入缓存句柄。
	 * <p>
	 * 句柄必须一起换：Mekanism 在配方重载时重建 input cache 实例，
	 * 继续持有旧实例会按过期配方表作答。
	 * <p>
	 * 解析失败只在同一配方版本内记一次（不重试）：否则损坏环境下每次候选判定
	 * 都要重跑一次注册表解析并清空整表，反而制造新的热点。
	 */
	private void refreshRecipeVersion() {
		long currentVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (observedRecipeVersion == currentVersion) return;
		plainItems.clearNow();
		componentKeys.clearNow();
		try {
			inputCache = MekanismRecipeType.SMELTING.getInputCache();
		} catch (LinkageError | RuntimeException error) {
			inputCache = null;
			LogThrottle.warn("ae2_smelting_input_cache_handle",
					"Mekanism SMELTING 输入缓存句柄解析失败，本轮配方版本内拒绝全部 smelt 候选: {}",
					error.toString());
		}
		observedRecipeVersion = currentVersion;
	}
}
