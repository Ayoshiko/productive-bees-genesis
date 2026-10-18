package com.ayoshiko.productivebeesgenesis.client.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.neoforged.fml.ModList;

import java.math.BigInteger;
import java.lang.reflect.Method;

/** 可选显示桥只处理文本，权威数量不经过浮点转换。 */
final class ResourceEmcFormat {
	private static Method formatter;
	private static boolean resolved;
	private ResourceEmcFormat() {}

	static String format(BigInteger amount) {
		if (!resolved) {
			resolved = true;
			if (ModList.get().isLoaded("projectexpansion")) {
				try {
					formatter = Class.forName("cool.furry.mc.neoforge.projectexpansion.util.EMCFormat")
							.getMethod("formatForceShort", BigInteger.class);
				} catch (ReflectiveOperationException | LinkageError failure) {
					ProductiveBeesGenesis.LOGGER.warn("Project Expansion EMC formatter unavailable", failure);
				}
			}
		}
		if (formatter != null) {
			try {
				return (String) formatter.invoke(null, amount);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
				formatter = null;
				ProductiveBeesGenesis.LOGGER.warn("Project Expansion EMC formatting failed", failure);
			}
		}
		return ResourceVisuals.compactAmount(amount);
	}
}
