package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.client.model.data.ModelProperty;

/**
 * 万象创世蜜脾块方块模型数据键
 * <br/>
 * 通过 NeoForge 的 {@link net.neoforged.neoforge.client.model.data.ModelData} 机制，
 * 将 CombBlockBlockEntity 的 combType 传递给 BakedModel，使方块渲染时
 * 可以根据 bee_type 切换纹理。
 */
public final class MyriadCombModelData {

	/** 蜜脾块的蜜蜂类型 ID（从 CombBlockBlockEntity.getCombType() 获取） */
	public static final ModelProperty<ResourceLocation> COMB_TYPE = new ModelProperty<>();

	private MyriadCombModelData() {
	}
}
