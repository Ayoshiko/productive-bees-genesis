package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;

import cy.jdkdigital.productivelib.event.CollectValidUpgradesEvent;
import cy.jdkdigital.productivelib.registry.LibItems;

import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;

/**
 * PB 升级物品有效性注册事件处理器（Task C-5）
 * <br/>
 * 监听 ProductiveLib 的 {@link CollectValidUpgradesEvent}，当方块实体实现
 * {@link IPbUpgradeProvider}（蜂箱含工厂版子类、离心机含工厂版子类）时，
 * 注册对应 PB 升级物品为有效升级，允许玩家潜行右键安装。
 * <p>
 * 原理：ProductiveLib 的 {@code InventoryHandlerHelper.UpgradeHandler#isValidUpgrade}
 * 通过 {@code NeoForge.EVENT_BUS.post(new CollectValidUpgradesEvent(...))} 动态查询
 * 当前方块实体支持哪些升级物品。未注册的升级物品无法插入处理器槽位。
 * <p>
 * 注册策略（按方块实体类型差异化）：
 * <ul>
 *   <li>蜂箱：8种（产量×4 + 时间×2 + 蜜脾块 + 基因采样器）</li>
 *   <li>离心机（含工厂版）：6种（产量×4 + 时间×2，不支持蜜脾块和基因采样器）</li>
 * </ul>
 * <p>
 * 注：ANTI_TELEPORT（防传送）和 RANGE（范围）升级对机械蜂箱模拟模式无效，已移除支持。
 * BREEDING（繁殖）升级从未实现，已移除。
 * <p>
 * <b>类加载安全</b>：通过 {@link IPbUpgradeProvider} 接口多态判定所有机器类型，
 * 不引用任何 ME/EME 可选依赖的具体类。蜂箱/离心机的区分通过
 * {@code instanceof TileEntityMekApiary}（蜂箱基础类，必选依赖）实现，
 * 非蜂箱的 IPbUpgradeProvider 实现者即离心机。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>单一职责：仅负责注册有效升级物品，不涉及效果计算</li>
 *   <li>开闭原则：新增升级类型仅需在此处追加 addValidUpgrade 调用</li>
 *   <li>依赖倒置：依赖 IPbUpgradeProvider 抽象而非具体子类</li>
 * </ul>
 * <p>
 * 线程安全：事件在主线程（服务端 tick 或玩家交互）触发，无需额外同步。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class CollectValidUpgradesEventHandler {

	private CollectValidUpgradesEventHandler() {
	}

	/**
	 * 处理升级有效性收集事件
	 * <br/>
	 * 当方块实体实现 {@link IPbUpgradeProvider} 时，注册对应 PB 升级物品为有效，允许玩家潜行右键安装。
	 * <p>
	 * 升级注册策略（按方块实体类型差异化）：
	 * <ul>
	 *   <li>蜂箱：8种（产量×4 + 时间×2 + 蜜脾块 + 基因采样器）</li>
	 *   <li>离心机（含工厂版）：6种（产量×4 + 时间×2，不支持蜜脾块和基因采样器）</li>
	 * </ul>
	 * <p>
	 * 蜂箱/离心机区分：{@link IPbUpgradeProvider} 的实现者只有蜂箱和离心机两类，
	 * 蜂箱基础类 {@link TileEntityMekApiary} 是必选依赖，所有蜂箱工厂子类都继承它，
	 * 因此 {@code instanceof TileEntityMekApiary} 可识别所有蜂箱，非蜂箱即离心机。
	 *
	 * @param event 升级收集事件
	 */
	@SubscribeEvent
	public static void onCollectValidUpgrades(CollectValidUpgradesEvent event) {
		BlockEntity be = event.getBlockEntity();
		// 接口多态判定 — 避免引用 ME/EME 可选依赖的具体离心机类
		if (!(be instanceof IPbUpgradeProvider)) {
			return;
		}
		// 蜂箱基础类是必选依赖，所有蜂箱工厂子类都继承它
		// IPbUpgradeProvider 实现者只有蜂箱和离心机两类，非蜂箱即离心机
		boolean isApiary = be instanceof TileEntityMekApiary;
		// 生产力升级（4种）— 蜂箱和离心机均支持
		event.addValidUpgrade(LibItems.UPGRADE_PRODUCTIVITY.get());
		event.addValidUpgrade(LibItems.UPGRADE_PRODUCTIVITY_2.get());
		event.addValidUpgrade(LibItems.UPGRADE_PRODUCTIVITY_3.get());
		event.addValidUpgrade(LibItems.UPGRADE_PRODUCTIVITY_4.get());
		// 时间升级（2种）— 蜂箱和离心机均支持
		event.addValidUpgrade(LibItems.UPGRADE_TIME.get());
		event.addValidUpgrade(LibItems.UPGRADE_TIME_2.get());
		if (isApiary) {
			// 蜜脾块升级 + 基因采样器升级 — 仅蜂箱支持
			event.addValidUpgrade(LibItems.UPGRADE_BLOCK.get());
			event.addValidUpgrade(LibItems.UPGRADE_GENE_SAMPLER.get());
		}
	}
}
