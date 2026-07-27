package com.ayoshiko.productivebeesgenesis.compat.mekanism_extras;

import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import com.jerry.mekextras.common.tier.ExtraFactoryTier;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.Map;

/**
 * Mekanism Extras (ME) 物品注册隔离类
 * <br/>
 * 将 ME 工厂 BlockItem（离心机 + 蜂箱）的注册逻辑从 {@link ModItems} 中抽取至此，
 * 使 {@link ModItems} 不再直接 import ME 的类。
 * <br/>
 * 本类直接引用 ME 的 {@link ExtraFactoryTier} 等类，
 * 因为仅在 ME 加载时由 {@link MECompatLoader} 调用。
 * <p>
 * 注册结果填充到 {@link ModItems#ME_FACTORY_ITEMS} 和 {@link ModItems#ME_APIARY_FACTORY_ITEMS}
 * （通配类型 {@code Map<Object, DeferredItem<?>>}，由主注册类定义），
 * 使用 {@link ModItems#machineItemProperties} 添加 Mekanism DataComponents。
 * <p>
 * 适配说明：{@link ModBlocks#ME_FACTORIES} 为通配类型 {@code Map<Object, DeferredBlock<?>>}，
 * 遍历时需将 {@code deferredBlock.get()} 强制转换为 {@link MekCentrifugeBlock} 或 {@link MekApiaryBlock}。
 */
public final class MEItemRegistration {

	private MEItemRegistration() {}

	/**
	 * 注册 ME 等级的离心机工厂 BlockItem
	 * <br/>
	 * 遍历 {@link ModBlocks#ME_FACTORIES} 中的方块，为每个方块注册同名的 BlockItem。
	 * 注册名与方块一致（如 absolute_extra_mek_centrifuge_factory），确保
	 * MekCentrifugeMEBlockType 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 */
	public static void registerFactoryItems() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		for (Map.Entry<Object, DeferredBlock<?>> entry : ModBlocks.ME_FACTORIES.entrySet()) {
			ExtraFactoryTier tier = (ExtraFactoryTier) entry.getKey();
			DeferredBlock<?> deferredBlock = entry.getValue();
			String registryName = tier.getAdvanceTier().getLowerName() + "_extra_mek_centrifuge_factory";
			// 延迟解析：deferredBlock.get() 必须在 supplier 内调用（Item RegisterEvent 阶段），
			// 不能在模组构造阶段调用（此时 Block RegisterEvent 未触发，DeferredHolder 未绑定）
			DeferredItem<ItemBlockMekCentrifuge> deferredItem = ModItems.ITEMS.register(registryName,
					() -> {
						MekCentrifugeBlock<?, ?> block = (MekCentrifugeBlock<?, ?>) deferredBlock.get();
						return new ItemBlockMekCentrifuge(block, ModItems.machineItemProperties(block));
					});
			ModItems.ME_FACTORY_ITEMS.put(tier, deferredItem);
		}
	}

	/**
	 * 注册 ME 等级的蜂箱工厂 BlockItem
	 * <br/>
	 * 遍历 {@link ModBlocks#ME_APIARY_FACTORIES} 中的方块，为每个方块注册同名的 BlockItem。
	 * 注册名与方块一致（如 absolute_extra_mek_apiary_factory），确保
	 * MekApiaryMEBlockType 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 * 使用 ItemBlockMekApiaryFactory（与原版工厂蜂箱相同的 ItemBlock 类）。
	 */
	public static void registerApiaryFactoryItems() {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		for (Map.Entry<Object, DeferredBlock<?>> entry : ModBlocks.ME_APIARY_FACTORIES.entrySet()) {
			ExtraFactoryTier tier = (ExtraFactoryTier) entry.getKey();
			DeferredBlock<?> deferredBlock = entry.getValue();
			String registryName = tier.getAdvanceTier().getLowerName() + "_extra_mek_apiary_factory";
			// 延迟解析：deferredBlock.get() 必须在 supplier 内调用（Item RegisterEvent 阶段）
			DeferredItem<ItemBlockMekApiaryFactory> deferredItem = ModItems.ITEMS.register(registryName,
					() -> {
						MekApiaryBlock<?, ?> block = (MekApiaryBlock<?, ?>) deferredBlock.get();
						return new ItemBlockMekApiaryFactory(block, ModItems.machineItemProperties(block));
					});
			ModItems.ME_APIARY_FACTORY_ITEMS.put(tier, deferredItem);
		}
	}
}
