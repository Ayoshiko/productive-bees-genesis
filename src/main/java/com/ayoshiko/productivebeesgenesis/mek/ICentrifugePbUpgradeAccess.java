package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import net.minecraft.nbt.CompoundTag;

import java.util.Map;

/**
	 * 离心机 PB 升级访问接口
	 * <br/>
	 * 抽象基础离心机（{@link MekCentrifugePbUpgradeHandler}）和工厂离心机
	 * （{@link FactoryPbUpgradeDelegate}）的公共 PB 升级 API，
	 * 供 {@link com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeDataHelper}
	 * 统一调用，消除基础离心机与工厂离心机在升级数据构建/应用逻辑上的重复。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>依赖倒置：Helper 依赖接口而非具体类，基础机和工厂机均可注入</li>
	 *   <li>最小接口：仅暴露 Helper 所需的 4 个方法，不暴露安装/卸载/倍率计算等内部逻辑</li>
	 * </ul>
	 *
	 * @since V17 代码审查修复
	 */
public interface ICentrifugePbUpgradeAccess {

	/**
	 * 获取 PB 升级数量映射（只读视图）
	 *
	 * @return PB 升级数量映射，键为类型，值为已安装数量
	 */
	Map<PbUpgradeType, Integer> getPbUpgradeCounts();

	/**
	 * 获取 PB 升级输入槽
	 *
	 * @return PB 升级输入槽（用于序列化/反序列化槽内物品）
	 */
	PbUpgradeInventorySlot getPbUpgradeInputSlot();

	/**
	 * 获取 PB 升级输出槽
	 *
	 * @return PB 升级输出槽（用于序列化/反序列化槽内物品）
	 */
	PbUpgradeInventorySlot getPbUpgradeOutputSlot();

	/**
	 * 从 NBT 加载 PB 升级数量（仅数量，不含槽位内容）
	 * <br/>
	 * 供升级数据恢复时调用，槽位内容由调用方通过
	 * {@link #getPbUpgradeInputSlot()} / {@link #getPbUpgradeOutputSlot()} 独立恢复。
	 *
	 * @param nbt 包含 PB 升级数量的 NBT（键为 {@link MekCentrifugePbUpgradeHandler#NBT_KEY_COUNTS}）
	 */
	void loadCounts(CompoundTag nbt);
}
