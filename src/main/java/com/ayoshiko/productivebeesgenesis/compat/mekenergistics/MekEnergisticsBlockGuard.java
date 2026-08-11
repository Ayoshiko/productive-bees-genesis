package com.ayoshiko.productivebeesgenesis.compat.mekenergistics;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;

/**
 * Mek-Energistics 安装器守卫谓词（普通工具类，非 Mixin）
 * <br/>
 * 从 {@code mixin.mekenergistics} 包迁出：Mixin 包内的非 Mixin 类被注入到
 * 第三方目标类后，会触发 Mixin 的 {@code IllegalClassLoadError} 校验崩溃
 * （issue #6）。本类放置于普通 compat 包，Mixin 注入代码可直接引用。
 * <p>
 * 任何由 ProductiveBeesGenesis 注册、路径属于离心机/蜂箱机器族的方块
 * （基础机器、原版等级、ME/EME 兼容等级）都绝不能被 mekenergistics
 * 工厂安装器转换为 ME 机器。
 */
public final class MekEnergisticsBlockGuard {

	private MekEnergisticsBlockGuard() {
	}

	/**
	 * @return true 当方块属于本模组的离心机/蜂箱机器族时
	 */
	public static boolean isProtectedMachine(Block block) {
		if (block == null) return false;
		ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
		if (id == null || !ProductiveBeesGenesis.MOD_ID.equals(id.getNamespace())) {
			return false;
		}
		String path = id.getPath();
		return path.contains("mek_centrifuge") || path.contains("mek_apiary");
	}
}
