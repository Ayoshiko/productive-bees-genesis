package com.ayoshiko.productivebeesgenesis.mek;

/**
	 * MEK 离心机 NBT 键名常量集中管理
	 * <br/>
	 * <b>设计原则：</b>
	 * <ul>
	 *   <li>SRP：仅负责 NBT 键名常量定义，不包含读写逻辑</li>
	 *   <li>OCP：新增键名只需新增常量字段，不修改已使用常量的代码</li>
	 *   <li>DRY：避免在多个文件中硬编码字符串字面量，防止拼写错误和不一致</li>
	 * </ul>
	 * <p>
	 * 键名约定：使用 {@code productivebeesgenesis_} 前缀 + snake_case,
	 * 与现有 PB 进度 / PB 升级 / AE2 状态等键名约定保持一致。
	 *
	 * @since Task 10
	 */
public final class MekCentrifugeNbtKeys {

	private MekCentrifugeNbtKeys() {
		// 工具类禁止实例化
	}

	/** 多流体槽 NBT 根键 — MultiFluidTankHolder 序列化的根 CompoundTag 键名 */
	public static final String NBT_KEY_MULTI_FLUID_TANKS = "productivebeesgenesis_multi_fluid_tanks";

	/**
	 * 基础离心机单流体槽 NBT 根键
	 * <br/>
	 * 修复 v14：基础离心机（非工厂版）使用单个 IExtendedFluidTank，
	 * 不通过 MultiFluidTankHolder 持久化，需独立序列化。
	 * 结构与蜂箱 {@code ApiaryNbtSerializer.NBT_KEY_APIARY_FLUID} 对齐，
	 * 均使用 "Fluid" 子标签包装 FluidStack.save(provider) 结果。
	 * 工厂版离心机已通过 {@link #NBT_KEY_MULTI_FLUID_TANKS} 持久化，本键仅基础版使用。
	 */
	public static final String NBT_KEY_CENTRIFUGE_FLUID = "productivebeesgenesis_centrifuge_fluid";

	// ===== 模块 3 Bug 1: 镐子破坏持久化 — 冗余槽位 NBT 键 =====
	// 以下 key 仅在 saveCustomDataForItem（扳手/镐子拆卸）路径写入，作为 MEK ITEM_CONTAINER 组件的冗余备份，
	// 确保 collectComponents 不完整时（如部分槽位未注册到 InventorySlotHolder）数据仍可恢复。

	/**
	 * NBT key — 输出槽列表（ListTag，包含主输出槽 + 副输出槽1 + 副输出槽2 的 serializeNBT）
	 * <br/>
	 * 模块 3 Bug 1：镐子破坏离心机时，输出槽物品通过 collectComponents 已写入 BLOCK_ENTITY_DATA，
	 * 但作为冗余备份，额外保存到自定义 NBT 键，确保极端场景下数据可恢复。
	 */
	public static final String NBT_KEY_DROP_OUTPUT_SLOTS = "productivebeesgenesis_drop_output_slots";

	/**
	 * NBT key — 输入槽（蜜脾槽，BasicInventorySlot.serializeNBT）
	 * <br/>
	 * 模块 3 Bug 1：输入槽物品的冗余备份。
	 */
	public static final String NBT_KEY_DROP_INPUT_SLOT = "productivebeesgenesis_drop_input_slot";

	/**
	 * NBT key — 能量槽（EnergyInventorySlot.serializeNBT）
	 * <br/>
	 * 模块 3 Bug 1：能量槽物品（燃料）的冗余备份。
	 */
	public static final String NBT_KEY_DROP_ENERGY_SLOT = "productivebeesgenesis_drop_energy_slot";
}
