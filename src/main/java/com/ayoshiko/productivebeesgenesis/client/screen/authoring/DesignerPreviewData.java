package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.nbt.CompoundTag;

/** 仅预览实例持有草稿；真实蜜蜂继续从 PB 的蜂种表读取数据。 */
public interface DesignerPreviewData {
	void productivebeesgenesis$setPreviewData(CompoundTag data);
	boolean productivebeesgenesis$isPreview();
}
