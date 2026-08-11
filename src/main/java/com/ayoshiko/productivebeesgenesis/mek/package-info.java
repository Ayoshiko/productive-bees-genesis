/**
	 * Mekanism 离心机集成包
	 * <br/>
	 * 包含自定义Mekanism离心机方块、方块实体、容器、GUI等：
	 * <ol>
	 *   <li>MekCentrifugeBlock/TileEntityMekCentrifuge — 基础离心机</li>
	 *   <li>TileEntityMekCentrifugeFactory — 原版4等级工厂（基础/高级/精英/终极）</li>
	 *   <li>TileEntityExtraMekCentrifugeFactory — ME扩展工厂（8个等级）</li>
	 *   <li>TileEntityEMExtraMekCentrifugeFactory — EME扩展工厂（4个等级）</li>
	 *   <li>PbRecipeProcessor — PB配方处理辅助类（消除三工厂代码重复）</li>
	 *   <li>PbRecipeEnergyCache — PB配方能量缓存与ticks计算（从PbRecipeProcessor抽取）</li>
	 *   <li>FactoryLayoutHelper — 工厂GUI布局计算</li>
	 * </ol>
	 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
