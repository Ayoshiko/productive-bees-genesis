package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.NotNull;

import mekanism.common.inventory.container.MekanismContainer;

/**
 * 基础MEK离心机持久化处理器
 * <br/>
 * Task 11 重构：从 {@link TileEntityMekCentrifuge} 抽取的 NBT 序列化/反序列化逻辑，
 * 封装 PbRecipeProcessor 的 saveAdditional/loadAdditional/addContainerTrackers 调用。
 * <p>
 * 设计原则：单一职责，只负责持久化逻辑，不涉及槽位管理或配方处理。
 */
class MekCentrifugeSaveHandler {

	/** PB配方处理器 — 提供 NBT 序列化/反序列化支持 */
	private final PbRecipeProcessor pbProcessor;

	MekCentrifugeSaveHandler(PbRecipeProcessor pbProcessor) {
		this.pbProcessor = pbProcessor;
	}

	/**
	 * 保存PB配方处理进度到NBT
	 * <br/>
	 * 委托给 {@link PbRecipeProcessor#saveAdditional}。
	 * 注意：NBT 格式从 putInt 改为 putIntArray（数组长度1），旧存档无法加载，
	 * 但模组暂未发布，无需兼容旧存档。
	 */
	void save(@NotNull CompoundTag nbt) {
		pbProcessor.saveAdditional(nbt);
	}

	/** 加载PB配方处理进度 — 委托给 PbRecipeProcessor */
	void load(@NotNull CompoundTag nbt) {
		pbProcessor.loadAdditional(nbt);
	}

	/**
	 * 同步PB进度到客户端
	 * <br/>
	 * 委托给 {@link PbRecipeProcessor#addContainerTrackers}，同步 pbOperatingTicks、
	 * pbProcessing、pbProcessingTime 数组（基础机器数组长度为1）。
	 */
	void addContainerTrackers(@NotNull MekanismContainer container) {
		pbProcessor.addContainerTrackers(container);
	}
}
