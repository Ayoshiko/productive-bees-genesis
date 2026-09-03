package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import javax.annotation.Nullable;

import java.util.Iterator;
import java.util.LinkedHashMap;

/**
 * Per-host bounded cache answering “can this machine actually process this comb?”.
 * <p>
 * 蜜脾在网络里是否「像蜜脾」由 {@link CombFuzzyMatcher#isCombItem} 按 Item 引用判断，
 * 但整合包里存在大量<b>只有蜂、没有离心配方</b>的蜜脾（本机无法处理，需其它模组机器，
 * 例如 chemlib 元素蜜脾走氧化机/分解机）。这类键若进入候选窗口，每轮都要付
 * 「toStack + 逐槽 validator + 配方查找」的空转成本，且永远进不到抽取阶段，
 * 因而连 per-key 退避都不会触发 —— 表现为持续小额开销的「卡但 TPS 不掉」。
 * <p>
 * 判定委托宿主的 {@code productivebeesgenesis$canProcessInput}，因此自动覆盖
 * 万象创世动态配方、PB 离心配方、熔炼兼容三条语义，无需在附属侧维护蜂种名单。
 * <p>
 * <b>缓存键是 (Item, bee_type) 而非 AEItemKey</b>：蜜脾能否被离心的唯一决定因素就是
 * 这两者（PB 配方 ingredient 是 bee_type 组件匹配，固定蜜脾的类型由 Item 决定），
 * 与 {@code InputValidationCache.InputFingerprint} / {@code RecipeCacheManager.CacheKey}
 * 同一套身份语义。这样可避开 {@code AEItemKey.equals}（走
 * {@code ItemStack.isSameItemSameComponents}，geckolib 还有 mixin 包裹）——
 * spark vVh8WfPCN3 实测按 AEItemKey 记忆化时 map 查找自身 self 时间即达 508ms。
 * <p>
 * <b>失效条件</b>：{@link ProductiveBeesGenesis#RECIPE_VERSION}（配方/标签重载）
 * 与 per-machine 熔炼兼容开关。后者必须纳入：固定蜜脾（ghostly/milky/powdery）与
 * 原版蜜脾不带 bee_type 组件，其可处理性依赖熔炼兼容是否开启。
 * <p>
 * <b>线程安全</b>：全部方法 synchronized。网格回调可能在非 tick 线程调用
 * {@link #clear()}，而 tick 线程正在查询；锁为 per-host，不会串行化不同机器。
 * <p>
 * <b>异常语义与 {@link Ae2SmeltingInputCache} 相反，是刻意的</b>：这里查询失败按
 * 「可处理」放行。误判为假会让合法蜜脾被永久饿死（功能回退，机器直接停摆），
 * 而误判为真只是退回修复前的空转成本，代价小得多。
 */
final class Ae2CombProcessableCache {

	/**
	 * 条目上限。取 2048 而非同族缓存的 1024：本整合包蜂种并集达 489 种，
	 * 每种对应「蜜脾 + 蜜脾块」两个身份（≈978），加固定/原版蜜脾已贴近 1024。
	 * 上限恰好卡在扫描量级时，10 tick 的全量刷新会按迭代顺序不断淘汰下一个要用的条目
	 * （LRU 遇顺序扫描的经典退化），等于缓存失效。留出余量避免该退化。
	 */
	static final int MAX_ENTRIES = 2_048;

	/**
	 * 单次拉取窗口内允许的「未缓存判定」次数上限。
	 * <p>
	 * 首次判定可能落到 {@code PbRecipeFinder} 的防御性全量遍历（本整合包 409 条离心配方），
	 * 而 10 tick 的候选刷新会一次性遍历整个网络类型表。若不限速，配方重载后的首轮刷新
	 * 会把「每类型一次全量遍历」堆到同一 tick，形成新的尖峰。超额的键本轮按放行处理
	 * 且<b>不写入缓存</b>，留给后续窗口判定：最坏情况只是暂时退回修复前的行为，不会饿死。
	 */
	static final int MAX_PROBES_PER_WINDOW = 64;

	/** 蜜脾身份：Item + bee_type（固定蜜脾的 bee_type 由 Item 推导，见 CombFuzzyMatcher）。 */
	private record CombIdentity(Item item, @Nullable ResourceLocation beeType) {
	}

	private final LinkedHashMap<CombIdentity, Boolean> entries = new LinkedHashMap<>(64, 0.75f, true);
	private long observedRecipeVersion = Long.MIN_VALUE;
	private boolean observedSmeltingEnabled;
	private int remainingProbes;

	/** 开启一个新的判定窗口（每次拉取调用一次），重置未缓存判定的限速额度。 */
	synchronized void beginProbeWindow() {
		remainingProbes = MAX_PROBES_PER_WINDOW;
	}

	/**
	 * 判断宿主是否真的能加工该蜜脾键。
	 * <p>
	 * 仅供候选分类阶段调用（每 10 tick 一次的候选刷新 + 条目极少的白名单直探路径）。
	 * 不得放进每 tick 每候选的谓词，那里连一次 map 查找都嫌贵。
	 *
	 * @param host            当前拉取宿主（提供本机配方语义）
	 * @param key             候选键
	 * @param smeltingEnabled 本机熔炼兼容开关快照（同时作为失效条件）
	 * @return true 表示本机存在可处理该输入的配方
	 */
	synchronized boolean canProcess(IAe2InputHost host, AEItemKey key, boolean smeltingEnabled) {
		if (key == null) return false;
		if (host == null) return true;
		refreshInvalidation(smeltingEnabled);
		CombIdentity identity = new CombIdentity(key.getItem(), CombFuzzyMatcher.getBeeType(key));
		Boolean cached = entries.get(identity);
		if (cached != null) return cached;
		// 限速用尽：本轮放行且不缓存，等下个窗口再判定（见 MAX_PROBES_PER_WINDOW）
		if (remainingProbes <= 0) return true;
		remainingProbes--;

		boolean result;
		try {
			// AE2 返回的只读栈不被本类修改或持有
			ItemStack probe = key.getReadOnlyStack();
			result = host.productivebeesgenesis$canProcessInput(probe);
		} catch (LinkageError | RuntimeException error) {
			// 失败按放行处理（见类注释）：宁可多探测，不可饿死合法蜜脾
			LogThrottle.warn("ae2_comb_processable_cache",
					"AE2 蜜脾可处理性判定异常，本次按可处理放行 key={}: {}", key, error.toString());
			result = true;
		}
		if (entries.size() >= MAX_ENTRIES) {
			Iterator<CombIdentity> iterator = entries.keySet().iterator();
			if (iterator.hasNext()) {
				iterator.next();
				iterator.remove();
			}
		}
		entries.put(identity, result);
		return result;
	}

	/** 清空缓存，例如 AE2 网格拓扑变化或配方重载后。 */
	synchronized void clear() {
		entries.clear();
		observedRecipeVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		remainingProbes = 0;
	}

	/** 当前缓存条目数，供诊断与测试使用。 */
	synchronized int size() {
		return entries.size();
	}

	/** 配方版本或熔炼开关变化即整表失效（两者都会改变判定结果）。 */
	private void refreshInvalidation(boolean smeltingEnabled) {
		long currentVersion = ProductiveBeesGenesis.RECIPE_VERSION.get();
		if (observedRecipeVersion == currentVersion && observedSmeltingEnabled == smeltingEnabled) return;
		entries.clear();
		observedRecipeVersion = currentVersion;
		observedSmeltingEnabled = smeltingEnabled;
	}
}
