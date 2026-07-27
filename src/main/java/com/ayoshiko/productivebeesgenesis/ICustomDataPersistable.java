package com.ayoshiko.productivebeesgenesis;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.HolderLookup;
import org.jetbrains.annotations.NotNull;

/**
 * 自定义数据持久化接口（Bug 6）
 * <br/>
 * 供扳手拆卸时保存机器特有数据到 BLOCK_ENTITY_DATA 组件。
 * 实现此接口的 TileEntity 在 {@code getDrops} 中被识别，
 * 调用 {@link #saveCustomDataForItem} 保存非 MEK 标准体系的数据。
 * <p>
 * 设计原则：接口隔离（ISP），仅包含扳手持久化所需的最小方法集。
 */
public interface ICustomDataPersistable {

	/**
	 * 保存机器特有数据为 NBT — 供扳手拆卸持久化使用
	 * <br/>
	 * 仅保存机器特有数据（如蜜蜂槽、PB配方进度等），
	 * 标准 MEK 数据由 collectComponents 通过 DataComponents 流转。
	 * 放置时通过 BLOCK_ENTITY_DATA 组件自动调用 loadAdditional 恢复。
	 *
	 * @param provider 注册表访问器
	 * @return 包含自定义数据的 NBT
	 */
	@NotNull
	CompoundTag saveCustomDataForItem(@NotNull HolderLookup.Provider provider);
}
