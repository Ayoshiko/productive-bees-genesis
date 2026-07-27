package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.component.TileComponentEjector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * TileComponentEjector的Accessor Mixin
 * <br/>
 * 提供对私有字段tickDelay和tile的访问，用于输出槽弹出速度优化。
 * <p>
 * 原理：Mekanism的TileComponentEjector硬编码tickDelay=10（半秒），
 * 通过此Accessor可在Mixin中修改tickDelay，实现MEK离心机的快速弹出。
 *
 * <p>
 * <b>版本敏感性</b>：本 @Accessor 依赖 Mekanism 10.7.19.85+ 的字段名稳定性。
 * 通过 {@code @Accessor} 访问 {@code TileComponentEjector#tickDelay}（private int）
 * 与 {@code TileComponentEjector#tile}（private final TileEntityMekanism）私有字段。
 * 如果 Mekanism 重命名上述任一字段，本 Mixin 将无法应用，需同步更新本类对应的 @Accessor target。
 *
 * @since 1.0.0
 */
@Mixin(TileComponentEjector.class)
public interface TileEntityEjectorAccessor {

	/**
	 * 访问 TileComponentEjector 的 private 字段 `tickDelay`（类型：`int`）
	 * <br/>
	 * 用于在 Mixin 中读取当前弹出延迟值（Mekanism 默认 10 tick = 半秒）。
	 *
	 * @return 原始 tickDelay 字段值
	 */
	@Accessor("tickDelay")
	int productivebeesgenesis$getTickDelay();

	/**
	 * 访问 TileComponentEjector 的 private 字段 `tickDelay`（类型：`int`）
	 * <br/>
	 * 用于在 Mixin 中修改 tickDelay，实现 MEK 离心机的快速弹出。
	 *
	 * @param value 要设置的 tickDelay 值
	 */
	@Accessor("tickDelay")
	void productivebeesgenesis$setTickDelay(int value);

	/**
	 * 访问 TileComponentEjector 的 private final 字段 `tile`（类型：`TileEntityMekanism`）
	 * <br/>
	 * 用于在 Mixin 中读取关联的 TileEntity 实例，进行进一步操作。
	 *
	 * @return 原始 tile 字段值
	 */
	@Accessor("tile")
	TileEntityMekanism productivebeesgenesis$getTile();
}
