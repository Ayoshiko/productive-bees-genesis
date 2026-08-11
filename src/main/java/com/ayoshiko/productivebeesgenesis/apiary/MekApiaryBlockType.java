package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekUpgradeSupport;
import mekanism.common.block.attribute.AttributeUpgradeable;
import mekanism.common.content.blocktype.BlockTypeTile;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.lib.transmitter.TransmissionType;

/**
	 * MEK通用机械蜂箱BlockType定义
	 * <br/>
	 * 参考MekCentrifugeBlockType模式，使用Machine.MachineBuilder构建基础机器BlockType。
	 * 通过createMachine()自动添加以下Attribute（由Mekanism内部完成）：
	 * <ul>
	 *   <li>AttributeElectricMachine — 标识电力机器（自动）</li>
	 *   <li>Attributes.ACTIVE_LIGHT — 活跃状态指示灯（自动）</li>
	 *   <li>AttributeUpgradeSupport.DEFAULT_MACHINE_UPGRADES — 支持速度+能量+消音升级（自动，随后被替换）</li>
	 *   <li>AttributeGui — GUI关联（通过withGui添加）</li>
	 * </ul>
	 * <p>
	 * 升级支持：通过{@link MekUpgradeSupport#forMachine()}替换默认的AttributeUpgradeSupport，
	 * MEKExtras加载时额外支持STACK/CREATIVE升级。
	 * <p>
	 * 设计原则：单一职责，本类仅负责BlockType定义，方块/方块实体/物品注册由init包负责。
	 * TileEntityType引用通过lazy supplier延迟解析，避免循环类加载依赖。
	 */
public final class MekApiaryBlockType {

	/**
	 * 基础MEK通用机械蜂箱BlockType
	 * <br/>
	 * 能量配置：usage=50L/tick，storage=20000L（与MEK离心机一致）。
	 * 侧面配置：物品/流体/能量三种传输类型。
	 * GUI关联：ModMenuTypes.MEK_APIARY（占位，后续Task 7完善Container）。
	 * 生产周期由TileEntityMekApiary构造函数传入（200 ticks = 10秒，MEK原版标准）。
	 * Task 4：移除 withSound(ENERGIZED_SMELTER)，工作声音改由 {@link ApiarySoundHandler} 播放 PB 蜜蜂嗡嗡声。
	 */
	public static final BlockTypeTile<TileEntityMekApiary> MEK_APIARY = Machine.MachineBuilder
			.createMachine(() -> ModBlockEntitiesHolder.MEK_APIARY, descriptionLang("mek_apiary"))
			// 基础蜂箱能耗由服务器配置提供；工厂版在 MekApiaryFactoryBlockType 中使用各等级固定基础值。
			.withEnergyConfig(() -> ModConfig.SERVER.apiaryEnergyPerTick.get(), () -> 20_000L)
			.withSideConfig(TransmissionType.ITEM, TransmissionType.FLUID, TransmissionType.ENERGY)
			.with(mekanism.common.block.attribute.Attributes.SECURITY)
			.withGui(() -> ModMenuTypes.MEK_APIARY)
			// Bug 8：添加 AttributeUpgradeable，指向基础工厂版蜂箱，使 Basic Tier Installer 能将初始蜂箱升级为工厂版
		.with(new AttributeUpgradeable(MekCentrifugeBlockType.wrapAsBlockRegistryObject(ModBlocks.BASIC_MEK_APIARY_FACTORY)))
		// 蜂箱不支持STACK/CREATIVE升级（CREATIVE导致TPS严重降低），仅支持SPEED/ENERGY/MUFFLING
		.with(MekUpgradeSupport.forApiary())
		.build();

	private MekApiaryBlockType() {}

	/**
	 * 创建通用机械蜂箱描述ILangEntry
	 * <br/>
	 * key格式：description.productivebeesgenesis.{key}
	 * 用于Shift+N显示方块描述文本。
	 */
	public static mekanism.api.text.ILangEntry descriptionLang(String key) {
		return () -> "description.productivebeesgenesis." + key;
	}

	/**
	 * TileEntityType持有者 — 由ModBlockEntities静态初始化时设置
	 * <br/>
	 * 解决BlockType与TileEntityType之间的循环依赖：
	 * BlockType需要引用TileEntityType（用于getTileType()），
	 * 而TileEntityType注册又需要引用BlockType（通过BlockType的get()方法）。
	 * 通过Holder静态字段延迟绑定，打破循环。
	 */
	public static class ModBlockEntitiesHolder {
		public static mekanism.common.registration.impl.TileEntityTypeRegistryObject<TileEntityMekApiary> MEK_APIARY;
	}
}
