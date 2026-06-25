package com.ayoshiko.productivebeesgenesis.menu;

import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityEMExtraMekCentrifugeFactory;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * EME扩展版离心机工厂Container
 * <br/>
 * 继承MekanismTileContainer，槽位由基类自动从方块实体提取。
 * <p>
 * 重写偏移方法以适配3行输出槽布局和EME等级的宽GUI：
 * - Y偏移135（对应inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠
 * - X偏移通过FactoryLayoutHelper的EMExtraFactoryTier重载方法动态计算，
 *   使用tier.inventoryLabelX（EME枚举直接存储了计算好的值）
 */
public class EMExtraMekCentrifugeFactoryContainer extends MekanismTileContainer<TileEntityEMExtraMekCentrifugeFactory> {

    public EMExtraMekCentrifugeFactoryContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, @NotNull TileEntityEMExtraMekCentrifugeFactory tile) {
        super(type, id, inv, tile);
    }

    /** Y偏移 — 3行输出槽布局需要更大的Y偏移 */
    @Override
    protected int getInventoryYOffset() {
        return 135;
    }

    /** X偏移 — 使用EME tier直接存储的inventoryLabelX值 */
    @Override
    protected int getInventoryXOffset() {
        int labelX = FactoryLayoutHelper.getInventoryLabelX(tile.tier);
        if (labelX > 0) {
            return labelX;
        }
        return super.getInventoryXOffset();
    }
}
