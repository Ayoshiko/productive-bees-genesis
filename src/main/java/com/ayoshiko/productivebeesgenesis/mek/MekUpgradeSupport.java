package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.api.Upgrade;
import mekanism.common.block.attribute.AttributeUpgradeSupport;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.interfaces.ITileUpgradable;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.UpgradeUtils;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
	 * 机器升级支持工具类
	 * <br/>
	 * 根据MEKExtras和MekanismEmpowered加载状态返回对应的{@link AttributeUpgradeSupport}：
	 * <ul>
	 *   <li>MEKExtras已加载：SPEED/ENERGY/MUFFLING + STACK/CREATIVE</li>
	 *   <li>MekanismEmpowered已加载：追加 EMPOWERED_SPEED/ENERGY + IO_CAPACITY + AUTO_INSERTER + FAST_ITEM_INSERT/EJECT</li>
	 *   <li>两者都未加载：SPEED/ENERGY/MUFFLING（即{@link AttributeUpgradeSupport#DEFAULT_MACHINE_UPGRADES}）</li>
	 * </ul>
	 * <p>
	 * 设计原理：MUFFLING是Mekanism原版升级，{@code Machine}构造函数通过
	 * {@code DEFAULT_MACHINE_UPGRADES}已默认包含。MEKExtras的STACK/CREATIVE
	 * 通过Mixin注入{@code mekanism.api.Upgrade}枚举，仅在MEKExtras加载时可用。
	 * MekanismEmpowered的升级为运行时注册的标准Upgrade实例，通过反射获取。
	 * <p>
	 * 类加载安全：本类不直接引用任何MEKExtras或MekanismEmpowered类，
	 * {@link MekExtraUpgradeSupport}和{@link MekEmpUpgradeSupport}仅在对应模组加载时
	 * 通过懒加载委托调用，避免未安装时触发NoClassDefFoundError。
	 * <p>
	 * 升级查询门面：{@link #getStackUpgrades}和{@link #hasCreativeUpgrade}提供
	 * 线程安全的间接查询入口，调用方（蜂箱/离心机）无需直接引用MEKExtras类。
	 */
public final class MekUpgradeSupport {

	private MekUpgradeSupport() {}

	/**
	 * 获取离心机升级支持属性
	 * <br/>
	 * 离心机有物品输入和输出，支持全部升级：
	 * <ul>
	 *   <li>原版：SPEED/ENERGY/MUFFLING</li>
	 *   <li>MEKExtras（若加载）：STACK/CREATIVE</li>
	 *   <li>MekanismEmpowered（若加载）：全部 6 种（EMPOWERED_SPEED/ENERGY + IO_CAPACITY + AUTO_INSERTER +
	 * FAST_ITEM_INSERT/EJECT）</li>
	 * </ul>
	 * <p>
	 * 调用时机：BlockType静态初始化时调用。此时Mixin已应用，
	 * 若MEKExtras加载则ExtraUpgrade字段已填充。
	 *
	 * @return 包含适当升级集合的AttributeUpgradeSupport实例
	 */
	public static AttributeUpgradeSupport forMachine() {
		// 构建基础升级列表（原版 + 可选 MEKExtras）
		List<Upgrade> base = collectBaseMachineUpgrades();
		// 追加 MekanismEmpowered 升级（若加载）
		if (MekCompatHooks.isMekanismEmpoweredLoaded()) {
			return MekEmpUpgradeSupport.createItemInOutMachineUpgrades(base);
		}
		return com.ayoshiko.productivebeesgenesis.util.ForgeUpgradeSupport.create(base.toArray(new Upgrade[0]));
	}

	/**
	 * 获取蜂箱专用升级支持属性
	 * <br/>
	 * 蜂箱支持原版SPEED/ENERGY/MUFFLING + MEKExtras的CREATIVE升级（若加载）+
	 * MekanismEmpowered／Unleashed 的全部六种升级（若加载），包含蜂笼与小食的快速输入。
	 * <p>
	 * 与{@link #forMachine()}的差异：STACK升级始终排除（2^n次产出倍率对蜂箱产量体系过高）。
	 * CREATIVE升级的历史TPS风险已被产出管线的20-tick批量聚合消除
	 * （{@code BeeSlotTickProcessor}的BATCH_FLUSH_INTERVAL + FLUSH_ACCUMULATION_THRESHOLD），
	 * 每 tick 产出仅累积计数，产出物品按批次合并插入。
	 * 离心机仍使用{@link #forMachine()}保留STACK/CREATIVE支持。
	 *
	 * @return 包含 SPEED/ENERGY/MUFFLING + 可选 CREATIVE/MekEmp 升级的支持属性
	 */
	public static AttributeUpgradeSupport forApiary() {
		List<Upgrade> base = new ArrayList<>(4);
		base.add(Upgrade.SPEED);
		base.add(Upgrade.ENERGY);
		base.add(Upgrade.MUFFLING);
		// MEKExtras CREATIVE 升级（若加载）— 通过包私有类间接添加，避免直接引用 ExtraUpgrade
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MekExtraUpgradeSupport.collectCreativeUpgrade(base);
		}
		// 蜂笼、小食输入与产物输出共用完整的 MekEmp 升级集合。
		if (MekCompatHooks.isMekanismEmpoweredLoaded()) {
			return MekEmpUpgradeSupport.createItemInOutMachineUpgrades(base);
		}
		return com.ayoshiko.productivebeesgenesis.util.ForgeUpgradeSupport.create(base.toArray(new Upgrade[0]));
	}

	/**
	 * 收集基础机器升级（原版SPEED/ENERGY/MUFFLING + 可选MEKExtras STACK/CREATIVE）
	 * <br/>
	 * MEKExtras升级通过{@link MekExtraUpgradeSupport#collectExtraUpgrades}间接添加，
	 * 避免本公共类直接引用MEKExtras类触发类加载风险。
	 *
	 * @return 基础升级列表
	 */
	private static List<Upgrade> collectBaseMachineUpgrades() {
		List<Upgrade> base = new ArrayList<>(5);
		base.add(Upgrade.SPEED);
		base.add(Upgrade.ENERGY);
		base.add(Upgrade.MUFFLING);
		// MEKExtras 升级（若加载）— 通过包私有类间接添加，避免直接引用 ExtraUpgrade
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			MekExtraUpgradeSupport.collectExtraUpgrades(base);
		}
		return base;
	}

	/**
	 * 从运行时 Mekanism 公式取得生产时间倍率，包含已安装附属的速度加成。
	 *
	 * @param tile 使用 Mekanism 升级组件的宿主
	 * @return 正有限时间倍率，最多为 1
	 */
	public static float getSpeedTimeMultiplier(ITileUpgradable tile) {
		// 大基数保留亚 tick 精度，也避免 Unleashed 的整数化吞掉普通速度加成。
		int sampleBase = Integer.MAX_VALUE;
		return speedTimeMultiplier(MekanismUtils.getTicks(tile, sampleBase), sampleBase);
	}

	static float speedTimeMultiplier(double scaledTicks, int sampleBase) {
		if (sampleBase <= 0 || !Double.isFinite(scaledTicks) || scaledTicks == 0.0D) {
			return 1.0f;
		}
		// Unleashed 以负倒数编码每 tick 操作数；先还原耗时，再除采样基数。
		double ticks = scaledTicks < 0.0D ? -1.0D / scaledTicks : scaledTicks;
		return SaturatingMath.positiveFiniteFloat(Math.min(1.0D, ticks / sampleBase), 1.0f);
	}

	/**
	 * 刷新普通／强化速度和能量变更后的真实耗电，包含解锁前置变动。
	 * ME／EME 工厂未调用原版能量重算，不能依赖 Empowered 只注入原版的分流。
	 *
	 * @param context 持有唯一机器能量容器的宿主
	 * @param upgrade 本次数量变更的升级
	 */
	public static void refreshEmpoweredEnergy(PbRecipeContext context, Upgrade upgrade) {
		if (!MekCompatHooks.isMekanismEmpoweredLoaded()
				|| !MekEmpUpgradeSupport.affectsEnergyUsage(upgrade)) {
			return;
		}
		var container = context.energyContainer();
		if (container != null) {
			container.updateEnergyPerTick();
		}
	}

	static int getEmpoweredEnergyUpgrades(ITileUpgradable tile) {
		return MekCompatHooks.isMekanismEmpoweredLoaded()
				? MekEmpUpgradeSupport.getEmpoweredEnergyUpgrades(tile) : 0;
	}

	/**
	 * 查询MEKExtras堆叠升级数量（门面方法）
	 * <br/>
	 * MEKExtras未加载时安全返回0。STACK升级影响并行处理数：
	 * {@code 2^stackUpgrades}倍（满级8=256倍）。
	 *
	 * @param tile 机器方块实体
	 * @return STACK升级数量，MEKExtras未加载时返回0
	 */
	public static int getStackUpgrades(TileEntityMekanism tile) {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			return MekExtraUpgradeSupport.getStackUpgrades(tile);
		}
		return 0;
	}

	/**
	 * 查询是否安装了MEKExtras创造升级（门面方法）
	 * <br/>
	 * MEKExtras未加载时安全返回false。CREATIVE升级提供零能量消耗。
	 *
	 * @param tile 机器方块实体
	 * @return true 如果安装了CREATIVE升级，MEKExtras未加载时返回false
	 */
	public static boolean hasCreativeUpgrade(TileEntityMekanism tile) {
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			return MekExtraUpgradeSupport.hasCreativeUpgrade(tile);
		}
		return false;
	}

	/**
	 * 判断Upgrade对象是否为MEKExtras的CREATIVE升级（门面方法）
	 *
	 * @param upgrade 升级类型
	 * @return true 如果是CREATIVE升级且MEKExtras已加载
	 */
	public static boolean isCreativeUpgrade(Upgrade upgrade) {
		return MekCompatHooks.isMekanismExtrasLoaded() && MekExtraUpgradeSupport.isCreativeUpgrade(upgrade);
	}

	/**
	 * 判断Upgrade对象是否为MEKExtras的STACK升级（门面方法）
	 *
	 * @param upgrade 升级类型
	 * @return true 如果是STACK升级且MEKExtras已加载
	 */
	public static boolean isStackUpgrade(Upgrade upgrade) {
		return MekCompatHooks.isMekanismExtrasLoaded() && MekExtraUpgradeSupport.isStackUpgrade(upgrade);
	}

	/**
	 * 获取升级显示信息 — STACK/CREATIVE 升级显示自定义文本
	 * <br/>
	 * 抽取自基础离心机和工厂版离心机的 getInfo 方法，消除重复逻辑：
	 * <ul>
	 *   <li>STACK升级：清除默认效率信息，显示"并行: Nx"（2^stackUpgrades）</li>
	 *   <li>CREATIVE升级：显示"效率: ∞"和"能耗: 0"</li>
	 *   <li>其他升级：显示MEK原版效率信息</li>
	 * </ul>
	 *
	 * @param tile    机器方块实体
	 * @param upgrade 升级类型
	 * @return 显示信息列表
	 */
	public static List<Component> getUpgradeInfo(TileEntityMekanism tile, Upgrade upgrade) {
		List<Component> ret = new ArrayList<>(UpgradeUtils.getMultScaledInfo(tile, upgrade));
		if (isStackUpgrade(upgrade)) {
			ret.clear();
			// 与 int 类型的运行时操作数使用同一饱和策略，避免 31/32 级显示虚高。
			double stack = stackUpgradeMultiplier(getStackUpgrades(tile));
			ret.add(Component.translatable("gui.productivebeesgenesis.upgrades.stack", stack));
		} else if (isCreativeUpgrade(upgrade)) {
			ret.add(Component.translatable("gui.mekanism.upgrades.effect", "∞"));
			ret.add(Component.translatable("gui.productivebeesgenesis.energy_consumption", 0));
		}
		return ret;
	}

	/** Return the display multiplier using the same positive-int ceiling as runtime operations. */
	static double stackUpgradeMultiplier(int stackUpgrades) {
		return SaturatingMath.saturatingPowerOfTwo(stackUpgrades);
	}
}
