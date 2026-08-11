package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import cy.jdkdigital.productivebees.setup.BeeReloadListener;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

/**
 * 蜜蜂类型 ID 解析与规范化工具（纯静态，无状态）
 * <br/>
 * 从 {@link BeeInfoHelper} 拆分而来，职责（SRP）：字符串 ID 解析与
 * PB 注册 ID 变体的规范化匹配，不持有任何缓存。
 */
public final class BeeTypeNormalizer {

	private BeeTypeNormalizer() {
	}

	/**
	 * 将字符串解析为 ResourceLocation
	 *
	 * @param id 字符串ID（如 "productivebees:iron"）
	 * @return 解析后的 ResourceLocation，解析失败返回 null
	 */
	@Nullable
	public static ResourceLocation parseBeeType(String id) {
		try {
			String trimmed = id.trim();
			if (trimmed.isEmpty()) return null;
			return ResourceLocation.parse(trimmed);
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("parseBeeType 解析异常: {}", id, e);
			return null;
		}
	}

	/**
	 * 规范化蜜蜂类型 ID（兼容旧数据与第三方 ID 写法）
	 * <br/>
	 * PB 的 {@link BeeReloadListener} 注册蜜蜂时会生成多个 ID 变体，
	 * 如 productivebees:industrialforegoing/ether_gas 与 productivebees:ether_gas 等价。
	 * 按原始 key 未命中时尝试去掉路径前缀的简化 ID，避免漏匹配
	 * （KubeJS 动态注册的蜜蜂同样适用）。
	 * <p>
	 * 仅当原始 ID 未命中时才尝试规范化，避免改变原始 ID 的语义；
	 * {@code BeeReloadListener.apply} 的 simpleId 规范化逻辑与此保持一致。
	 *
	 * @param beeType 原始蜜蜂类型 ID
	 * @return 规范化后的蜜蜂类型 ID；未注册时返回原始 ID（保持 miss 语义）
	 */
	public static ResourceLocation resolveLoadedBeeType(ResourceLocation beeType) {
		if (BeeReloadListener.INSTANCE.getData(beeType) != null) {
			return beeType;
		}
		String path = beeType.getPath();
		int slash = path.lastIndexOf('/');
		if (slash > 0) {
			ResourceLocation simple = ResourceLocation.fromNamespaceAndPath(
					beeType.getNamespace(), path.substring(slash + 1));
			if (BeeReloadListener.INSTANCE.getData(simple) != null) {
				return simple;
			}
		}
		return beeType;
	}
}
