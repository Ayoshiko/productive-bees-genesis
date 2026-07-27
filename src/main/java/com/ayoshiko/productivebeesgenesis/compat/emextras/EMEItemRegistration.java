package com.ayoshiko.productivebeesgenesis.compat.emextras;

import com.ayoshiko.productivebeesgenesis.apiary.ItemBlockMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import com.ayoshiko.productivebeesgenesis.mek.ItemBlockMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;

import java.util.Map;

/**
 * EvolvedMekanismExtras (EME) 物品注册隔离类
 * <br/>
 * 将 EME 工厂 BlockItem（离心机 + 蜂箱）的注册逻辑从 {@link ModItems} 中抽取至此，
 * 使 {@link ModItems} 不再直接 import EME 的类。
 * <br/>
 * 本类直接引用 EME 的 {@link EMExtraFactoryTier} 等类，
 * 因为仅在 EME 加载时由 {@link EMECompatLoader} 调用。
 * <p>
 * 注册结果填充到 {@link ModItems#EME_FACTORY_ITEMS} 和 {@link ModItems#EME_APIARY_FACTORY_ITEMS}
 * （通配类型 {@code Map<Object, DeferredItem<?>>}，由主注册类定义），
 * 使用 {@link ModItems#machineItemProperties} 添加 Mekanism DataComponents。
 * <p>
 * 适配说明：{@link ModBlocks#EME_FACTORIES} 为通配类型 {@code Map<Object, DeferredBlock<?>>}，
 * 遍历时需将 {@code deferredBlock.get()} 强制转换为 {@link MekCentrifugeBlock} 或 {@link MekApiaryBlock}。
 */
public final class EMEItemRegistration {

	private EMEItemRegistration() {}

	/**
	 * 注册 EME 等级的离心机工厂 BlockItem
	 * <br/>
	 * 遍历 {@link ModBlocks#EME_FACTORIES} 中的方块，为每个方块注册同名的 BlockItem。
	 * 注册名与方块一致（如 absolute_overclocked_emextra_mek_centrifuge_factory），确保
	 * MekCentrifugeBlockType 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 */
	public static void registerFactoryItems() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		for (Map.Entry<Object, DeferredBlock<?>> entry : ModBlocks.EME_FACTORIES.entrySet()) {
			EMExtraFactoryTier tier = (EMExtraFactoryTier) entry.getKey();
			DeferredBlock<?> deferredBlock = entry.getValue();
			String registryName = tier.getEMExtraTier().getLowerName() + "_emextra_mek_centrifuge_factory";
			// 延迟解析：deferredBlock.get() 必须在 supplier 内调用（Item RegisterEvent 阶段），
			// 不能在模组构造阶段调用（此时 Block RegisterEvent 未触发，DeferredHolder 未绑定）
			DeferredItem<ItemBlockMekCentrifuge> deferredItem = ModItems.ITEMS.register(registryName,
					() -> {
						MekCentrifugeBlock<?, ?> block = (MekCentrifugeBlock<?, ?>) deferredBlock.get();
						return new ItemBlockMekCentrifuge(block, ModItems.machineItemProperties(block));
					});
			ModItems.EME_FACTORY_ITEMS.put(tier, deferredItem);
		}
	}

	/**
	 * 注册 EME 等级的蜂箱工厂 BlockItem
	 * <br/>
	 * 遍历 {@link ModBlocks#EME_APIARY_FACTORIES} 中的方块，为每个方块注册同名的 BlockItem。
	 * 注册名与方块一致（如 absolute_overclocked_emextra_mek_apiary_factory），确保
	 * MekApiaryEMEBlockType 中 wrapAsBlockRegistryObject() 创建的 Item DeferredHolder 能正确解析。
	 * 使用 ItemBlockMekApiaryFactory（与原版工厂蜂箱相同的 ItemBlock 类）。
	 */
	public static void registerApiaryFactoryItems() {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		for (Map.Entry<Object, DeferredBlock<?>> entry : ModBlocks.EME_APIARY_FACTORIES.entrySet()) {
			EMExtraFactoryTier tier = (EMExtraFactoryTier) entry.getKey();
			DeferredBlock<?> deferredBlock = entry.getValue();
			String registryName = tier.getEMExtraTier().getLowerName() + "_emextra_mek_apiary_factory";
			// 延迟解析：deferredBlock.get() 必须在 supplier 内调用（Item RegisterEvent 阶段）
			DeferredItem<ItemBlockMekApiaryFactory> deferredItem = ModItems.ITEMS.register(registryName,
					() -> {
						MekApiaryBlock<?, ?> block = (MekApiaryBlock<?, ?>) deferredBlock.get();
						return new ItemBlockMekApiaryFactory(block, ModItems.machineItemProperties(block));
					});
			ModItems.EME_APIARY_FACTORY_ITEMS.put(tier, deferredItem);
		}
	}
}
