package com.ayoshiko.productivebeesgenesis.init;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.client.ClientDevModeState;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
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
						output.accept(ModItems.BYPRODUCT_DESTRUCTION_UPGRADE.get());
						// 使用 SERVER 配置并加 isLoaded 保护，避免多人游戏客户端未加载服务端配置时崩溃
						boolean isServerLoaded = ModConfig.areServerSpecsLoaded();
						// 万象创世蜜蜂总开关：禁用后隐藏所有万象创世相关物品
						boolean myriadEnabled = isServerLoaded && ModConfig.SERVER.myriadCreationsEnabled.get();
						// 开发者模式由命令控制，客户端通过 ClientDevModeState 镜像状态决定开发物品可见性
						boolean devModeEnabled = ClientDevModeState.isEnabled();
						if (myriadEnabled && devModeEnabled) {
							output.accept(ModItems.INFINITY_CREATION_COMB.get());
							output.accept(ModItems.INFINITY_CREATION_COMB_BLOCK_ITEM.get());
						}
						// 添加所有MEK离心机方块（按指定顺序）
						// 1. 基础机器
						output.accept(ModItems.MEK_CENTRIFUGE.get());
						// 1.5. MEK通用机械蜂箱
						output.accept(ModItems.MEK_APIARY.get());
						// 1.6. MEK通用机械蜂箱工厂版（基础→高级→精英→终极）
						output.accept(ModItems.BASIC_MEK_APIARY_FACTORY.get());
						output.accept(ModItems.ADVANCED_MEK_APIARY_FACTORY.get());
						output.accept(ModItems.ELITE_MEK_APIARY_FACTORY.get());
						output.accept(ModItems.ULTIMATE_MEK_APIARY_FACTORY.get());
						// 1.7. ME等级蜂箱工厂（Mekanism Extras）：绝对→至尊→寰宇支配→悖论无限
						if (MekCompatHooks.isMekanismExtrasLoaded()) {
							addMEApiaryFactoryItemsInOrder(output);
						}
						// 1.7.5. EM等级蜂箱工厂（Evolved Mekanism）：超频→量子→致密→多元宇宙→创造
						if (MekCompatHooks.isEvolvedMekanismLoaded()) {
							addEMApiaryFactoryItemsInOrder(output);
						}
						// 1.8. EME等级蜂箱工厂（Evolved Mekanism Extras）：绝对超频→至尊量子→宇宙致密→无限多元
						if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
							addEMEApiaryFactoryItemsInOrder(output);
						}
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
	 * <br/>
	 * 通过 {@link MekCompatHooks#getMEFactoryTiers()} 反射获取 ME 工厂等级列表，
	 * 避免主注册类编译期依赖 ME 类。{@link ModItems#ME_FACTORY_ITEMS} 为通配类型，
	 * 需将 {@code item.get()} 强制转换为 {@link Item}。
	 */
	private static void addMEFactoryItemsInOrder(CreativeModeTab.Output output) {
		// ABSOLUTE → SUPREME → COSMIC → INFINITE
		for (Object tier : MekCompatHooks.getMEFactoryTiers()) {
			DeferredItem<?> item = ModItems.ME_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept((Item) item.get());
			}
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
	 * <br/>
	 * 通过 {@link MekCompatHooks#getEMEFactoryTiers()} 反射获取 EME 工厂等级列表，
	 * 避免主注册类编译期依赖 EME 类。{@link ModItems#EME_FACTORY_ITEMS} 为通配类型，
	 * 需将 {@code item.get()} 强制转换为 {@link Item}。
	 */
	private static void addEMEFactoryItemsInOrder(CreativeModeTab.Output output) {
		// ABSOLUTE_OVERCLOCKED → SUPREME_QUANTUM → COSMIC_DENSE → INFINITE_MULTIVERSAL
		for (Object tier : MekCompatHooks.getEMEFactoryTiers()) {
			DeferredItem<?> item = ModItems.EME_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept((Item) item.get());
			}
		}
	}

	/**
	 * 按顺序添加ME等级蜂箱工厂物品（Mekanism Extras）
	 * 顺序：绝对→至尊→寰宇支配→悖论无限
	 * <br/>
	 * 通过 {@link MekCompatHooks#getMEFactoryTiers()} 反射获取 ME 工厂等级列表，
	 * 避免主注册类编译期依赖 ME 类。{@link ModItems#ME_APIARY_FACTORY_ITEMS} 为通配类型，
	 * 需将 {@code item.get()} 强制转换为 {@link Item}。
	 */
	private static void addMEApiaryFactoryItemsInOrder(CreativeModeTab.Output output) {
		// ABSOLUTE → SUPREME → COSMIC → INFINITE
		for (Object tier : MekCompatHooks.getMEFactoryTiers()) {
			DeferredItem<?> item = ModItems.ME_APIARY_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept((Item) item.get());
			}
		}
	}

	/**
	 * 按顺序添加EM等级蜂箱工厂物品（Evolved Mekanism）
	 * 顺序：超频→量子→致密→多元宇宙→创造
	 */
	private static void addEMApiaryFactoryItemsInOrder(CreativeModeTab.Output output) {
		// OVERCLOCKED → QUANTUM → DENSE → MULTIVERSAL → CREATIVE
		for (FactoryTier tier : MekCompatHooks.getEMFactoryTiers()) {
			DeferredItem<ItemBlockMekApiaryFactory> item = ModItems.EM_APIARY_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept(item.get());
			}
		}
	}

	/**
	 * 按顺序添加EME等级蜂箱工厂物品（Evolved Mekanism Extras）
	 * 顺序：绝对超频→至尊量子→宇宙致密→无限多元
	 * <br/>
	 * 通过 {@link MekCompatHooks#getEMEFactoryTiers()} 反射获取 EME 工厂等级列表，
	 * 避免主注册类编译期依赖 EME 类。{@link ModItems#EME_APIARY_FACTORY_ITEMS} 为通配类型，
	 * 需将 {@code item.get()} 强制转换为 {@link Item}。
	 */
	private static void addEMEApiaryFactoryItemsInOrder(CreativeModeTab.Output output) {
		// ABSOLUTE_OVERCLOCKED → SUPREME_QUANTUM → COSMIC_DENSE → INFINITE_MULTIVERSAL
		for (Object tier : MekCompatHooks.getEMEFactoryTiers()) {
			DeferredItem<?> item = ModItems.EME_APIARY_FACTORY_ITEMS.get(tier);
			if (item != null && item.get() != null) {
				output.accept((Item) item.get());
			}
		}
	}

	private ModCreativeTabs() {}
}
