package com.ayoshiko.productivebeesgenesis.datagen;

import net.minecraft.resources.ResourceLocation;

/**
 * 可选模组条件解析工具
 * <br/>
 * 根据方块注册 ID 判断所属可选模组（EM/ME/EME）。
 * 供 {@link ModBlockTagsProvider} 共用（DRY），用于决定方块是否需要标记为可选标签条目。
 * <p>
 * 命名规则（与 ModBlocks 注册名一致）：
 * <ul>
 *   <li>_emextra_ 前缀 → emextras（EME）</li>
 *   <li>_extra_ 前缀 → mekanism_extras（ME）</li>
 *   <li>overclocked_/quantum_/dense_/multiversal_/creative_ 前缀 → evolvedmekanism（EM）</li>
 * </ul>
 */
public final class ModLoadedConditionResolver {

	private ModLoadedConditionResolver() {}

	/**
	 * 根据方块注册 ID 解析所属可选模组 ID。
	 *
	 * @param id 方块注册 ID
	 * @return 模组 ID（如 "evolvedmekanism"），无条件方块返回 null
	 */
	public static String resolveModId(ResourceLocation id) {
		String path = id.getPath();
		// EME 必须先判断（EME 路径含 _emextra_，也含 EM 前缀如 absolute_overclocked_）
		if (path.contains("_emextra_")) return "emextras";
		if (path.contains("_extra_")) return "mekanism_extras";
		if (path.startsWith("overclocked_") || path.startsWith("quantum_")
				|| path.startsWith("dense_") || path.startsWith("multiversal_")
				|| path.startsWith("creative_")) {
			return "evolvedmekanism";
		}
		return null;
	}
}
