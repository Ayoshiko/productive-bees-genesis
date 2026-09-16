package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneValue;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * 基因小食自动喂食的语义与接线回归测试。
 * <p>
 * {@link GeneAttributeRanking} 是纯函数，可直接断言；喂食器本身依赖方块实体与
 * 服务端世界，无法在普通 JVM 单测中构造，因此按本仓库既有做法锁定源码级契约。
 */
class GeneTreatAutoFeederTest {

	private static final String FEEDER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/GeneTreatAutoFeeder.java";
	private static final String TREAT_FEEDER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiaryHoneyTreatFeeder.java";
	private static final String SLOT_MANAGER =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/ApiarySlotManager.java";
	private static final String CAGE_TICK =
			"src/main/java/com/ayoshiko/productivebeesgenesis/apiary/CageTickProcessor.java";

	// ===== 属性方向语义（本次修复的核心正确性） =====

	@Test
	void temperRanksLowerValuesAsBetter() {
		// PB 的 temper 序号越大越凶，passive(0) 才是玩家想要的；期望度必须翻转
		int passive = GeneAttributeRanking.desirability(
				GeneAttribute.TEMPER, GeneValue.TEMPER_PASSIVE);
		int hostile = GeneAttributeRanking.desirability(
				GeneAttribute.TEMPER, GeneValue.TEMPER_HOSTILE);
		assertTrue(passive > hostile,
				"性格越温顺期望度越高，否则自动喂食会把蜜蜂越喂越凶");
		assertEquals(0, GeneAttributeRanking.desirability(
				GeneAttribute.TEMPER, GeneValue.TEMPER_HOSTILE));
	}

	@Test
	void normalAttributesKeepAscendingOrder() {
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_VERY_HIGH)
				> GeneAttributeRanking.desirability(
						GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_NORMAL));
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.WEATHER_TOLERANCE, GeneValue.WEATHER_TOLERANCE_ANY)
				> GeneAttributeRanking.desirability(
						GeneAttribute.WEATHER_TOLERANCE, GeneValue.WEATHER_TOLERANCE_NONE));
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.BEHAVIOR, GeneValue.BEHAVIOR_METATURNAL)
				> GeneAttributeRanking.desirability(
						GeneAttribute.BEHAVIOR, GeneValue.BEHAVIOR_DIURNAL));
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.ENDURANCE, GeneValue.ENDURANCE_STRONG)
				> GeneAttributeRanking.desirability(
						GeneAttribute.ENDURANCE, GeneValue.ENDURANCE_WEAK));
	}

	@Test
	void nullAndEmptyValuesRankAsWorst() {
		assertEquals(0, GeneAttributeRanking.desirability(GeneAttribute.PRODUCTIVITY, null));
		assertEquals(0, GeneAttributeRanking.desirability(
				GeneAttribute.PRODUCTIVITY, GeneValue.EMPTY));
	}

	@Test
	void typeAttributeIsNotRankable() {
		assertFalse(GeneAttributeRanking.isRankable(GeneAttribute.TYPE),
				"TYPE 是蜂种不是属性值，参与排序会触发 GeneSampleProfile 抛异常");
		assertTrue(GeneAttributeRanking.isRankable(GeneAttribute.PRODUCTIVITY));
	}

	// ===== 喂食调度契约 =====

	@Test
	void feederUsesDesirabilityInsteadOfRawGeneValue() throws Exception {
		String source = Files.readString(Path.of(FEEDER));
		assertTrue(source.contains("GeneAttributeRanking.desirability"),
				"必须用期望度判断能否提升，不能直接比较 GeneValue.getValue()");
		assertFalse(source.contains("target.getValue() > current"),
				"裸 getValue 比较会让性格属性反向喂食");
	}

	@Test
	void feederSkipsZeroPurityGenesAndTypeTreats() throws Exception {
		String source = Files.readString(Path.of(FEEDER));
		assertTrue(source.contains("purity <= 0"),
				"纯度 0 的基因几乎不会命中，不应据此判定可提升并持续消耗小食");
		assertTrue(source.contains("HoneyTreat.hasBeeType(treat)"),
				"含 TYPE 的小食在 PB 原版不会施加基因，必须拒绝避免白扣");
	}

	@Test
	void feederIsThrottledByRealGameTicks() throws Exception {
		String source = Files.readString(Path.of(FEEDER));
		assertTrue(source.contains("FEED_INTERVAL_TICKS = 5"),
				"喂食要创建临时实体，必须节流避免工厂版 tick 尖峰");
		assertTrue(source.contains("level.getGameTime()"),
				"节流必须基于真实游戏刻，tick 加速下才不会放大实体创建开销");
		assertTrue(source.contains("if (lastFeedTick == now) return false;"),
				"时间加速器在同一游戏刻重复调用时不得重复创建实体");
		assertTrue(source.contains("Math.floorMod(now + feedPhase, FEED_INTERVAL_TICKS)"),
				"不同位置的蜂箱必须错峰，避免同刻实体创建尖峰");
	}

	@Test
	void feederCachesGenesAndInvalidatesOnComponentChanges() throws Exception {
		String source = Files.readString(Path.of(FEEDER));
		assertTrue(source.contains("getCachedTreatGenes(treat)"));
		assertTrue(source.contains("ItemStack.isSameItemSameComponents(treat, cachedTreatSnapshot)"),
				"同一栈被自动化原地改写组件后必须重新解析基因");
		assertTrue(source.contains("cachedTreatSnapshot = treat.copyWithCount(1)"),
				"缓存快照不应因正常扣减数量而失效");
	}

	@Test
	void feederPrefersSelectedSlotThenLargestGap() throws Exception {
		String source = Files.readString(Path.of(FEEDER));
		assertTrue(source.contains("tile.getSelectedBeeSlot()"),
				"玩家点选蜜蜂格子后必须只喂那一只");
		assertTrue(source.contains("improvementGap(slots[selected], genes) > 0"));
		assertTrue(source.contains("gap > bestGap"),
				"未选中时应喂属性缺口最大的蜜蜂");
	}

	// ===== 自动化路径的安全性 =====

	@Test
	void automatedPathDoesNotCallVanillaInteractWithNullPlayer() throws Exception {
		String source = Files.readString(Path.of(TREAT_FEEDER));
		assertFalse(source.contains("interactLivingEntity(\n\t\t\t\t\t\t\tsingleTreat, null"),
				"PB 原版 interactLivingEntity 内部无条件 player.swing()，传 null 会 NPE");
		assertFalse(source.contains(", null, bee, InteractionHand"),
				"自动化路径不得把 null player 传进原版交互方法");
		assertTrue(source.contains("applyTreatWithoutPlayer"),
				"自动化路径应按原版语义逐步复刻，而不是传 null 玩家");
		assertTrue(source.contains("HoneyTreat.applyGenesToBee"),
				"基因施加仍须复用 PB 原版静态方法，保证概率与纯度语义一致");
	}

	@Test
	void automatedPathInitializesAttributeHandlerBeforeApplying() throws Exception {
		String source = Files.readString(Path.of(TREAT_FEEDER));
		assertTrue(source.contains("bee.getData(ProductiveBees.ATTRIBUTE_HANDLER)"),
				"刷怪蛋直接放入的蜜蜂需要先初始化属性附件，否则喂食无效");
	}

	@Test
	void treatIsOnlyConsumedAfterSuccessfulWriteBack() throws Exception {
		String source = Files.readString(Path.of(TREAT_FEEDER));
		assertTrue(source.contains("if (!fed) return false;"),
				"喂食失败不得扣除小食");
		int shrinkIndex = source.indexOf("treatSlot.shrinkStack(1, Action.EXECUTE)");
		int guardIndex = source.lastIndexOf("if (!fed) return false;");
		assertTrue(shrinkIndex > guardIndex && guardIndex >= 0,
				"消耗必须发生在成功回写之后");
	}

	// ===== 输入槽接线 =====

	@Test
	void cageInputSlotAcceptsGeneTreats() throws Exception {
		String source = Files.readString(Path.of(SLOT_MANAGER));
		assertTrue(source.contains("isGeneTreat(stack)"),
				"蜂笼输入槽必须接受带基因小食，否则玩家根本放不进去");
		assertTrue(source.contains("HoneyTreat.hasGene(stack)"),
				"只接受带基因的小食，无基因小食自动化收益为零");
	}

	@Test
	void autoFeedRunsBeforeCageProcessing() throws Exception {
		String source = Files.readString(Path.of(CAGE_TICK));
		int feedIndex = source.indexOf("autoFeeder.tryAutoFeed()");
		int cageIndex = source.indexOf("slotManager.processCageInput()");
		assertTrue(feedIndex >= 0 && cageIndex > feedIndex,
				"喂食必须先于蜂笼处理，保证共用输入槽时语义清晰");
	}
}
