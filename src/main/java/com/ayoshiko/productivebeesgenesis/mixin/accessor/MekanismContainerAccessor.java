package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.ISyncableData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * MekanismContainer 访问器：暴露 trackedData 供能量条节流替换使用
 * <br/>
 * Mekanism 在 {@code TileEntityMekanism#addContainerTrackers} 中为每个能量容器注册
 * {@code SyncableLong}（storedEnergy 高频 tracker，每 gameTick pull-diff，变化即发包）。
 * 能量条节流需要在 super 注册区段内识别 storedEnergy tracker 并替换为节流版，
 * 通过本 Accessor 暴露 {@code trackedData} 列表实现定点移除。
 * <p>
 * <b>版本敏感性</b>：依赖 Mekanism 10.7.19.85 的 {@code trackedData} 字段名。
 * Mekanism 重命名字段时本 Mixin 需同步更新。
 *
 * @since 1.0.2
 */
@Mixin(value = MekanismContainer.class, remap = false)
public interface MekanismContainerAccessor {

	@Accessor("trackedData")
	List<ISyncableData> productivebeesgenesis$trackedData();
}
