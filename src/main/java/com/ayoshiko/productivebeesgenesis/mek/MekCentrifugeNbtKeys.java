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
}
