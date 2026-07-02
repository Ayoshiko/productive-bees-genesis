package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.function.Supplier;

/**
 * 创造模式标签页注册
 * <br/>
 * 条件性添加MEK离心机方块（仅当Mekanism加载时显示）。
 * EM加载时额外添加5个EM等级工厂方块到标签页。
 */
public final class ModCreativeTabs {

	public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
			DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ProductiveBeesGenesis.MOD_ID);

	/** 模组标签页 */
	public static final Supplier<CreativeModeTab> MEK_CENTRIFUGE_TAB = CREATIVE_MODE_TABS.register(
			"mek_centrifuge_tab",
			() -> CreativeModeTab.builder()
					.title(Component.translatable("itemGroup.productivebeesgenesis"))
					.icon(() -> new ItemStack(ModItems.MEK_CENTRIFUGE.get()))
					.displayItems((parameters, output) -> {
						// 使用 SERVER 配置并加 isLoaded 保护，避免多人游戏客户端未加载服务端配置时崩溃
						boolean isServerLoaded = ModConfig.SERVER_SPEC.isLoaded();
						// 万象创世蜜蜂总开关：禁用后隐藏所有万象创世相关物品
						boolean myriadEnabled = isServerLoaded && ModConfig.SERVER.myriadCreationsEnabled.get();
						// 开发者模式：开启后才能在创造模式物品栏看到无尽·创世蜜脾、蜜脾块和寰宇支配之剑（验证）
						boolean devModeEnabled = isServerLoaded && ModConfig.SERVER.devMode.get();
						if (myriadEnabled && devModeEnabled) {
							output.accept(ModItems.INFINITY_CREATION_COMB.get());
							output.accept(ModItems.INFINITY_CREATION_COMB_BLOCK_ITEM.get());
							output.accept(ModItems.INFINITY_SWORD_REPLICA.get());
						}
						// 添加所有MEK离心机方块（按指定顺序）
						// 1. 基础机器
						output.accept(ModItems.MEK_CENTRIFUGE.get());
						// 2. 原版工厂等级（基础→高级→精英→终极）
						output.accept(ModItems.BASIC_MEK_CENTRIFUGE_FACTORY.get());
						output.accept(ModItems.ADVANCED_MEK_CENTRIFUGE_FACTORY.get());
						output.accept(ModItems.ELITE_MEK_CENTRIFUGE_FACTORY.get());
						output.accept(ModItems.ULTIMATE_MEK_CENTRIFUGE_FACTORY.get());
						// 3. ME等级（Mekanism Extras）：绝对→至尊→寰宇支配→悖论无限
						if (MekCompatHooks.isMekanismExtrasLoaded()) {
							addMEFactoryItemsInOrder(output);
						}
						// 4. EM等级（Evolved Mekanism）：超频→量子→致密→多元宇宙→创造
						if (MekCompatHooks.isEvolvedMekanismLoaded()) {
							addEMFactoryItemsInOrder(output);
						}
						// 5. EME等级（Evolved Mekanism Extras）：绝对超频→至尊量子→宇宙致密→无限多元
						if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
							addEMEFactoryItemsInOrder(output);
						}
					})
					.build()
	);

	/**
	 * 按顺序添加ME等级工厂物品（Mekanism Extras）
	 * 顺序：绝对→至尊→寰宇支配→悖论无限
	 */
	private static void addMEFactoryItemsInOrder(CreativeModeTab.Output output) {
		// ABSOLUTE → SUPREME → COSMIC → INFINITE
		addMEItemIfPresent(output, ExtraFactoryTier.ABSOLUTE);
		addMEItemIfPresent(output, ExtraFactoryTier.SUPREME);
		addMEItemIfPresent(output, ExtraFactoryTier.COSMIC);
		addMEItemIfPresent(output, ExtraFactoryTier.INFINITE);
	}

	/**
	 * 添加单个ME等级工厂物品（如果存在）
	 */
	private static void addMEItemIfPresent(CreativeModeTab.Output output, ExtraFactoryTier tier) {
		DeferredItem<ItemBlockMekCentrifuge> item = ModItems.ME_FACTORY_ITEMS.get(tier);
		if (item != null && item.get() != null) {
			output.accept(item.get());
		}
	}

	/**
	 * 按顺序添加EM等级工厂物品（Evolved Mekanism）
	 * 顺序：超频→量子→致密→多元宇宙→创造
	 */
	private static void addEMFactoryItemsInOrder(CreativeModeTab.Output output) {
		// OVERCLOCKED → QUANTUM → DENSE → MULTIVERSAL → CREATIVE
		for (FactoryTier tier : MekCompatHooks.getEMFactoryTiers()) {
			DeferredItem<ItemBlockMekCentrifuge> item = ModItems.EM_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept(item.get());
			}
		}
	}

	/**
	 * 按顺序添加EME等级工厂物品（Evolved Mekanism Extras）
	 * 顺序：绝对超频→至尊量子→宇宙致密→无限多元
	 */
	private static void addEMEFactoryItemsInOrder(CreativeModeTab.Output output) {
		// ABSOLUTE_OVERCLOCKED → SUPREME_QUANTUM → COSMIC_DENSE → INFINITE_MULTIVERSAL
		for (EMExtraFactoryTier tier : EMExtraFactoryTier.values()) {
			DeferredItem<ItemBlockMekCentrifuge> item = ModItems.EME_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept(item.get());
			}
		}
	}

	private ModCreativeTabs() {}
}
