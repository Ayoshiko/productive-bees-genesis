/**
	 * MEK通用机械蜂箱集成包
	 * <br/>
	 * 包含基于 Mekanism TileEntityElectricMachine 的通用机械蜂箱组件：
	 * <ol>
	 *   <li>MekApiaryBlock/TileEntityMekApiary — 基础机械蜂箱方块与方块实体</li>
	 *   <li>ApiarySlotManager — 蜜蜂槽/输出槽/蜂笼槽/能量槽/流体罐管理与NBT同步</li>
	 *   <li>FeederSlotManager — 喂食器窗口花朵槽管理</li>
	 *   <li>ApiaryTickHandler — 服务端 tick 与蜜蜂生产逻辑编排</li>
	 *   <li>BeeProduceProcessor — 蜜蜂产出查询与产物分发</li>
	 *   <li>ApiaryUpgradeHandler — PB升级效果计算</li>
	 *   <li>BeeSlot/BeeState — 蜜蜂数据模型与状态枚举</li>
	 * </ol>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
