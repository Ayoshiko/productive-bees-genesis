package com.ayoshiko.productivebeesgenesis.menu;

import com.ayoshiko.productivebeesgenesis.mek.FactoryLayoutHelper;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.registration.impl.ContainerTypeRegistryObject;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.tile.factory.TileEntityFactory;
import net.minecraft.world.entity.player.Inventory;
import org.jetbrains.annotations.NotNull;

/**
 * MEK离心机Container
 * <br/>
 * 继承MekanismTileContainer，槽位由基类自动从方块实体提取。
 * 基类自动处理：升级槽、输入/输出/能量槽、侧面配置同步、红石控制同步。
 * <p>
 * 工厂版重写偏移方法以适配3行输出槽布局：
 * - Y偏移135（对应inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠
 * - X偏移通过FactoryLayoutHelper动态计算，支持原版ULTIMATE与EM高等级
 */
public class MekCentrifugeContainer<TILE extends TileEntityMekanism> extends MekanismTileContainer<TILE> {

    public MekCentrifugeContainer(ContainerTypeRegistryObject<?> type, int id, Inventory inv, @NotNull TILE tile) {
        super(type, id, inv, tile);
    }

    /**
     * 工厂版Y偏移 — 3行输出槽布局需要更大的Y偏移
     * <br/>
     * 固定135（inventoryLabelY=125+10），避免与副输出槽2(y=97)重叠。
     * EM高等级无需调整，本项目布局固定为3行输出槽。
     */
    @Override
    protected int getInventoryYOffset() {
        if (tile instanceof TileEntityFactory<?>) {
            return 135;
        }
        return super.getInventoryYOffset();
    }

    /**
     * 工厂版X偏移 — 使用FactoryLayoutHelper动态计算，支持原版ULTIMATE与EM高等级
     * <br/>
     * 原版ULTIMATE：imageWidthAddition=34，偏移=addition/2=17（原版行为）
     * EM高等级：参考EM FactoryContainerMixin的动态居中公式
     *   offset = base + (imageWidth/2 - inventorySize/2)
     *   imageWidth = 176 + addition，inventorySize = 9*20 = 180
     */
    @Override
    protected int getInventoryXOffset() {
        if (tile instanceof TileEntityFactory<?> factory) {
            int imageWidthAddition = FactoryLayoutHelper.getImageWidthAddition(factory.tier);
            if (imageWidthAddition > 0) {
                if (FactoryLayoutHelper.isEMHighTier(factory.tier)) {
                    // EM高等级：动态居中公式，与EM原生FactoryContainerMixin一致
                    int imageWidth = 176 + imageWidthAddition;
                    return super.getInventoryXOffset() + (imageWidth / 2 - 90);
                }
                // 原版ULTIMATE：偏移addition/2以居中（34/2=17）
                return super.getInventoryXOffset() + imageWidthAddition / 2;
            }
        }
        return super.getInventoryXOffset();
    }
}
