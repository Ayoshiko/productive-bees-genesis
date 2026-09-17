package com.ayoshiko.productivebeesgenesis.storageprototype.compat;

import java.io.IOException;
import net.minecraft.world.level.storage.DimensionDataStorage;

/** 已绑定域使用 get；不存在或解码失败都不能重新创建同身份空域。 */
public final class ExistingDomainLoader {
	private ExistingDomainLoader() { }

	public static PrototypeSavedData load(DimensionDataStorage storage, String id) throws IOException {
		PrototypeSavedData data = storage.get(PrototypeSavedData.FACTORY, id);
		if (data == null) throw new IOException("D02 domain unavailable; preserve file and enter recovery: " + id);
		return data;
	}
}
