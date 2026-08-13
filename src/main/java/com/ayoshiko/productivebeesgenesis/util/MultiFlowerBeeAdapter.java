package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.FeederSlotManager;
import cy.jdkdigital.productivebees.init.ModTags;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
	 * 多花蜜脾蜜蜂适配器（模块 1 修复）
	 * <br/>
	 * 适配 PB 原版的 lumber_bee/quarry_bee/dye_bee 等多花蜜脾蜜蜂。
	 * 这些蜜蜂的产出依赖实体蜜蜂的 savedFlowerPos，机械蜂箱无实体无法直接支持。
	 * 本适配器通过喂食槽内容推断产物，复刻 PB 原版 BeeHelper.getBeeProduce 的动态产物分支。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅负责 multi-flower 蜜蜂的产物推断，不涉及配方查询或概率判定</li>
	 *   <li>开闭原则：新增 multi-flower 蜜蜂类型只需在 STRATEGIES 中注册新策略，无需修改调用方</li>
	 *   <li>依赖倒置：依赖 FeederSlotManager 抽象而非具体实现</li>
	 * </ul>
	 *
	 * @since 2.0.9
	 */
public final class MultiFlowerBeeAdapter {

	/**
	 * 多花蜜脾蜜蜂类型 → 策略映射
	 * <br/>
	 * PB 原版 BeeHelper.getBeeProduce 第 387-436 行的 if-else 链转为策略表，
	 * 避免 if-else 链膨胀，新增蜜蜂类型只需添加策略。
	 * <p>
	 * 不含 wanna_bee：战利品表逻辑复杂（需读取琥珀中实体），机械蜂箱无实体架构差异大，单独任务处理。
	 */
	private static final Map<ResourceLocation, MultiFlowerStrategy> STRATEGIES = Map.of(
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "lumber_bee"),
			new LumberStrategy(),
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "quarry_bee"),
			new QuarryStrategy(),
			ResourceLocation.fromNamespaceAndPath(PBConstants.PRODUCTIVE_BEES_MOD_ID, "dye_bee"),
			new DyeStrategy()
	);

	private MultiFlowerBeeAdapter() {
		// 工具类禁止实例化
	}

	/**
	 * 该蜜蜂类型是否为多花蜜脾蜜蜂
	 *
	 * @param beeTypeKey 蜜蜂类型 ID
	 * @return true 如果该蜜蜂为多花蜜蜂，需要走喂食槽推断产出路径
	 */
	public static boolean isMultiFlowerBee(ResourceLocation beeTypeKey) {
		return STRATEGIES.containsKey(BeeTypeNormalizer.resolveLoadedBeeType(beeTypeKey));
	}

	/**
	 * 从喂食槽推断多花蜜脾蜜蜂的产物
	 * <br/>
	 * 复刻 PB 原版 BeeHelper.getBeeProduce 的 lumber/quarry/dye 分支：
	 * 读取喂食槽中匹配标签的物品种，随机返回一个作为产物。
	 * <p>
	 * 返回的 ItemStack 会被 BeeProduceProcessor 包装为 ChancedOutput（chance=1.0），
	 * 由 BeeProduceBatchSampler 处理 rolls 倍率。
	 *
	 * @param beeTypeKey 蜜蜂类型 ID
	 * @param feeder     喂食槽管理器（null 时返回空列表）
	 * @param level      世界实例（染料蜜蜂花→染料合成查询需要）
	 * @return 产物列表（单个随机物品），喂食槽无匹配返回空列表
	 */
	public static List<ItemStack> sampleProduceFromFeeder(
			ResourceLocation beeTypeKey, @Nullable FeederSlotManager feeder, @Nullable Level level) {
		if (feeder == null) return List.of();
		MultiFlowerStrategy strategy = STRATEGIES.get(BeeTypeNormalizer.resolveLoadedBeeType(beeTypeKey));
		if (strategy == null) return List.of();
		ItemStack sample = strategy.sampleFromFeeder(feeder, level);
		return sample.isEmpty() ? List.of() : List.of(sample);
	}

	/**
	 * 多花蜜蜂策略接口
	 * <br/>
	 * 每种 multi-flower 蜜蜂对应一个策略，封装产物标签和采样方式。
	 */
	private interface MultiFlowerStrategy {
		ItemStack sampleFromFeeder(FeederSlotManager feeder, @Nullable Level level);
	}

	/**
	 * Lumber Bee 策略 — 从喂食槽匹配 productivebees:flowers/lumber 方块标签
	 * <br/>
	 * 复刻 PB BeeHelper.getBeeProduce 第 387-390 行：
	 * getFloweringBlockFromTag(level, flowerPos, ModTags.LUMBER, beeEntity)
	 */
	private static final class LumberStrategy implements MultiFlowerStrategy {
		@Override
		public ItemStack sampleFromFeeder(FeederSlotManager feeder, @Nullable Level level) {
			return feeder.getRandomBlockFromFeeder(ModTags.LUMBER);
		}
	}

	/**
	 * Quarry Bee 策略 — 从喂食槽匹配 productivebees:flowers/quarry 方块标签
	 * <br/>
	 * 复刻 PB BeeHelper.getBeeProduce 第 391-394 行：
	 * getFloweringBlockFromTag(level, flowerPos, ModTags.QUARRY, beeEntity)
	 */
	private static final class QuarryStrategy implements MultiFlowerStrategy {
		@Override
		public ItemStack sampleFromFeeder(FeederSlotManager feeder, @Nullable Level level) {
			return feeder.getRandomBlockFromFeeder(ModTags.QUARRY);
		}
	}

	/**
	 * Dye Bee 策略 — 花 → 染料
	 * <br/>
	 * 复刻 PB BeeHelper.getBeeProduce 第 395-398 行：
	 * 喂食槽中随机取一朵 minecraft:flowers 方块标签内的花，再通过合成配方转换为对应染料。
	 * 植物魔法（Botania）神秘花/神秘蘑菇直接映射为对应颜色的神秘花瓣。
	 * 喂食槽内没有花时回退到旧行为：直接取 c:dyes 物品标签内的染料产物（向后兼容）。
	 */
	private static final class DyeStrategy implements MultiFlowerStrategy {
		@Override
		public ItemStack sampleFromFeeder(FeederSlotManager feeder, @Nullable Level level) {
			// 1. PB 原版语义：从喂食槽随机取一朵花（minecraft:flowers 方块标签）
			ItemStack flower = feeder.getRandomBlockFromFeeder(net.minecraft.tags.BlockTags.FLOWERS);
			if (!flower.isEmpty() && level != null) {
				// 1a. Botania 神秘花/神秘蘑菇 → 对应颜色神秘花瓣
				ItemStack petal = DyeProduceResolver.resolveBotaniaPetal(flower);
				if (!petal.isEmpty()) return petal;
				// 1b. 花 → 染料（单原料合成配方输出）
				ItemStack dye = DyeProduceResolver.resolveDyeFromFlower(level, flower);
				if (!dye.isEmpty()) return dye;
			}
			// 2. 向后兼容：喂食槽直接放入染料时产出该染料
			return feeder.getRandomItemFromFeeder(ModTags.Common.DYES);
		}
	}
}
