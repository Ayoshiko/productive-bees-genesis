package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import cy.jdkdigital.productivebees.common.item.HoneyTreat;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneGroup;
import cy.jdkdigital.productivebees.util.GeneValue;
import mekanism.common.inventory.slot.BasicInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.List;

/**
 * 基因小食自动喂食器（智能属性提升调度）
 * <br/>
 * 服务端 tick 驱动：读取蜂笼输入槽（{@code cageInSlot}）中的带基因小食，解析其基因列表，
 * 再从蜜蜂槽中挑选「喂食后确实能提升属性」的蜜蜂喂食。喂食复用
 * {@link ApiaryHoneyTreatFeeder} 的实体路径（临时蜜蜂实体 + PB 原版
 * {@code HoneyTreat.interactLivingEntity}），保证基因概率、幼蜂成长、治疗行为与手持喂食一致。
 * <p>
 * <b>目标选择策略</b>（按优先级）：
 * <ol>
 *   <li><b>玩家选中槽</b>：{@link TileEntityMekApiary#getSelectedBeeSlot()} 已设置且该槽蜜蜂
 *       确有可提升属性时，只喂该槽——对应「玩家点击蜜蜂格子指定喂谁」</li>
 *   <li><b>缺口最大的蜜蜂</b>：扫描全部蜜蜂槽，喂「离小食提供的属性差距最大」的那只，
 *       即最需要这份基因的蜜蜂（例如放入产量/天气耐受/昼夜双行性小食时，
 *       会优先喂这几项最低的蜜蜂）；缺口相同时取索引更小者，保证行为可预测</li>
 *   <li><b>无可提升蜜蜂</b>：不动作、不消耗小食（避免满属性蜂箱空转吞小食）</li>
 * </ol>
 * <p>
 * <b>关键正确性约束</b>：
 * <ul>
 *   <li>「提升」按 {@link GeneAttributeRanking} 的期望度判定，而非裸 {@code getValue()}：
 *       性格（TEMPER）越低越好，方向与其他属性相反</li>
 *   <li>含 {@link GeneAttribute#TYPE} 的小食被整体拒绝：PB 原版对这类小食走
 *       {@code invalid_use} 分支，不施加任何基因，喂了纯属白扣物品</li>
 *   <li>纯度 0 的基因项不计入缺口：PB 施加概率为 {@code random(100) <= purity}，
 *       purity=0 时几乎必然失败，不应因此判定「可提升」而持续消耗</li>
 * </ul>
 * <p>
 * <b>性能</b>：{@link #FEED_INTERVAL_TICKS} 节流（默认每 20 tick 一次 = 每秒最多喂一只），
 * 避免 45 槽工厂版在 tick 加速下每 tick 创建临时蜜蜂实体造成的 CPU 尖峰；
 * 输入槽为空时提前短路，稳态零开销。
 * <p>
 * 线程安全：仅服务端 tick 线程调用；无共享可变状态（节流计数器为实例私有）。
 */
class GeneTreatAutoFeeder {

	/**
	 * 自动喂食间隔（tick）
	 * <br/>
	 * 每次喂食都要创建并加载一个临时蜜蜂实体（{@code EntityType.create} + {@code bee.load}），
	 * 单次成本远高于普通槽位扫描。20 tick（1 秒）一次在「自动化足够快」与
	 * 「大规模工厂不产生 tick 尖峰」之间取平衡，且与 PB 原版手动喂食的节奏接近。
	 */
	private static final int FEED_INTERVAL_TICKS = 20;

	/** 错误日志节流（tick 模式，避免持续异常每秒刷屏） */
	private static final LogThrottle ERROR_THROTTLE = new LogThrottle(100L, 5000L);

	private final TileEntityMekApiary tile;
	private final ApiarySlotManager slotManager;

	/** 上次执行喂食尝试的游戏刻（-1 表示尚未执行过） */
	private long lastFeedTick = -1L;

	/**
	 * 构造自动喂食器
	 *
	 * @param tile        蜂箱方块实体
	 * @param slotManager 蜜蜂槽管理器
	 */
	GeneTreatAutoFeeder(TileEntityMekApiary tile, ApiarySlotManager slotManager) {
		this.tile = tile;
		this.slotManager = slotManager;
	}

	/**
	 * 每服务端 tick 尝试喂食一只蜜蜂（受 {@link #FEED_INTERVAL_TICKS} 节流）。
	 * <br/>
	 * 输入槽为空 / 物品非带基因小食 / 含 TYPE 基因 / 无可提升蜜蜂时不做任何操作，不消耗物品。
	 * 小食与蜂笼共用同一输入槽，本方法在 {@link ApiarySlotManager#processCageInput} 之前调用，
	 * 但小食既非蜂笼也非刷怪蛋，蜂笼处理器会直接跳过，二者互不干扰。
	 *
	 * @return true 表示本次成功喂食了一只蜜蜂（小食已消耗 1 个）
	 */
	boolean tryAutoFeed() {
		try {
			// 1. 便宜的短路检查放最前：输入槽空 / 非小食时不进入节流记账，稳态零开销
			BasicInventorySlot cageInSlot = slotManager.getCageInSlot();
			if (cageInSlot == null || cageInSlot.isEmpty()) return false;
			ItemStack treat = cageInSlot.getStack();
			if (!ApiarySlotManager.isGeneTreat(treat)) return false;

			// 2. 节流：实体创建路径较贵，限制为每 FEED_INTERVAL_TICKS 一次
			if (!tryBeginFeedWindow()) return false;

			List<GeneGroup> genes = HoneyTreat.getGenes(treat);
			if (genes.isEmpty()) return false;
			// PB 对含 TYPE 的小食走 invalid_use 分支，不施加任何基因：整体拒绝，避免白扣
			if (HoneyTreat.hasBeeType(treat)) return false;

			int targetIndex = selectTarget(genes);
			if (targetIndex < 0) return false;

			// 复用实体喂食路径，成功后从小食所在槽消耗 1 个
			return ApiaryHoneyTreatFeeder.feedGeneTreatFromSlot(tile, targetIndex, treat, cageInSlot);
		} catch (Exception e) {
			long gameTime = tile.getLevel() == null ? 0L : tile.getLevel().getGameTime();
			ERROR_THROTTLE.tryLog(gameTime, suppressed -> ProductiveBeesGenesis.LOGGER.error(
					"基因小食自动喂食异常（已抑制 {} 次）", suppressed, e));
			return false;
		}
	}

	/**
	 * 节流窗口判定 — 距上次尝试满 {@link #FEED_INTERVAL_TICKS} 才放行。
	 * <br/>
	 * 使用真实游戏刻而非调用计数，使 tick 加速（JDTE / 时间权杖）下的喂食速率
	 * 仍与真实时间挂钩，不会因加速倍率放大实体创建开销。
	 * 同时兼容存档回档 / 世界切换造成的游戏刻回退（检测到回退即重置窗口）。
	 *
	 * @return true 表示本 tick 允许执行一次喂食尝试
	 */
	private boolean tryBeginFeedWindow() {
		var level = tile.getLevel();
		if (level == null) return false;
		long now = level.getGameTime();
		// 回退（回档/换世界）时重置，避免 lastFeedTick 停留在未来导致永久不喂
		if (lastFeedTick >= 0 && now >= lastFeedTick && now - lastFeedTick < FEED_INTERVAL_TICKS) {
			return false;
		}
		lastFeedTick = now;
		return true;
	}

	/**
	 * 挑选喂食目标蜜蜂槽索引。
	 * <br/>
	 * 优先级：玩家选中槽 → 缺口最大的蜜蜂。
	 *
	 * @param genes 小食携带的基因列表（已确保不含 TYPE）
	 * @return 目标蜜蜂槽索引；无可提升蜜蜂返回 -1
	 */
	private int selectTarget(List<GeneGroup> genes) {
		BeeSlot[] slots = slotManager.getBeeSlots();
		int selected = tile.getSelectedBeeSlot();
		// 1. 玩家选中槽 — 只要该槽蜜蜂确有可提升属性就独占喂食权（尊重玩家显式意图）
		if (selected >= 0 && selected < slots.length && improvementGap(slots[selected], genes) > 0) {
			return selected;
		}
		// 2. 扫描全部蜜蜂槽，喂「缺口最大」的那只；相同缺口取索引更小者（行为可预测）
		int bestIndex = -1;
		int bestGap = 0;
		for (int i = 0; i < slots.length; i++) {
			int gap = improvementGap(slots[i], genes);
			if (gap > bestGap) {
				bestGap = gap;
				bestIndex = i;
			}
		}
		return bestIndex;
	}

	/**
	 * 计算该蜜蜂相对这份小食的「可提升缺口」总量。
	 * <br/>
	 * 缺口 = 所有属性上「小食期望度 − 蜜蜂当前期望度」的正值之和。
	 * 返回 0 表示这只蜜蜂在该小食覆盖的属性上已不低于小食水平，喂了没有收益。
	 * <p>
	 * 期望度由 {@link GeneAttributeRanking} 提供，已处理 TEMPER 的反向语义。
	 * 纯度 0 的基因项被跳过：PB 的施加判定为 {@code random.nextInt(100) <= purity}，
	 * purity=0 时命中概率仅 1%，不足以支撑「持续消耗小食」的判定。
	 *
	 * @param slot  候选蜜蜂槽
	 * @param genes 小食基因列表
	 * @return 可提升缺口总量（0 表示无收益）
	 */
	private static int improvementGap(BeeSlot slot, List<GeneGroup> genes) {
		if (slot == null || slot.isEmpty()) return 0;
		GeneSampleProfile profile = slot.getGeneSampleProfile();
		if (profile == null) return 0;

		int totalGap = 0;
		for (int i = 0; i < genes.size(); i++) {
			GeneGroup gene = genes.get(i);
			GeneAttribute attribute = gene.attribute();
			if (attribute == null || !GeneAttributeRanking.isRankable(attribute)) continue;
			// 纯度 0 视为无效基因项：施加概率过低，不应据此判定"可提升"
			Integer purity = gene.purity();
			if (purity == null || purity <= 0) continue;

			GeneValue target = GeneValue.byName(gene.value());
			if (target == null) continue;
			int targetScore = GeneAttributeRanking.desirability(attribute, target);
			int currentScore = GeneAttributeRanking.desirability(attribute, profile.value(attribute));
			if (targetScore > currentScore) {
				totalGap += targetScore - currentScore;
			}
		}
		return totalGap;
	}
}
