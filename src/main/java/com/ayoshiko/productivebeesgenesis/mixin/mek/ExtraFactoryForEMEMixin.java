package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekanism_extras.common.content.blocktype.ExtraFactory;
import com.jerry.mekanism_extras.common.content.blocktype.ExtraMachine;
import com.jerry.mekanism_extras.common.tier.ExtraFactoryTier;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeUpgradeable;
import mekanism.common.block.attribute.AttributeFactoryType;
import mekanism.common.content.blocktype.BlockType;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.registration.impl.BlockRegistryObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.RegistryObject;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
	 * 注入ME的ExtraFactory构造器，为ABSOLUTE离心机工厂添加EME升级链
	 * <br/>
	 * 当EME加载时，ME ABSOLUTE离心机工厂需要EMExtraAttributeUpgradeable指向EME ABSOLUTE_OVERCLOCKED离心机工厂，
	 * 使ME ABSOLUTE离心机可以通过EME的ItemEMExtraTierInstaller升级到EME ABSOLUTE_OVERCLOCKED离心机。
	 * <p>
	 * EME 模组自身注入 ExtraFactory 的 Mixin 只为ALLOYING类型添加ExtraAttributeUpgradeable，不为SMELTING类型添加
	 * EMExtraAttributeUpgradeable。本Mixin为SMELTING类型的ABSOLUTE等级添加EMExtraAttributeUpgradeable，
	 * 实现ME → EME的跨升级系统升级。
	 * <p>
	 * 注意：当前我们的离心机工厂使用ExtraFactoryMachine基类直接创建，不经过ExtraFactory构造器，
	 * 所以此Mixin在当前实现中不会被触发。保留此Mixin作为扩展点，若将来改用ExtraFactory基类则自动生效。
	 * 实际的EMExtraAttributeUpgradeable添加由MekCentrifugeBlockType.initEMETiers()处理。
	 */
@Mixin(value = ExtraFactory.class, remap = false, priority = 1200)
public abstract class ExtraFactoryForEMEMixin extends BlockType {

	private ExtraFactoryForEMEMixin() {
		super(null);
	}

	@Inject(method = "<init>", at = @At("RETURN"), remap = false, require = 0)
	private void productivebeesgenesis$onInit(Supplier<?> tileEntityRegistrar, Supplier<?> containerRegistrar,
			ExtraMachine.ExtraFactoryMachine<?> origMachine,
			ExtraFactoryTier tier, CallbackInfo ci) {
		if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			return;
		}
		// 仅处理ABSOLUTE等级（ME → EME的入口等级）
		if (tier != ExtraFactoryTier.ABSOLUTE) {
			return;
		}
		// 仅处理SMELTING类型
		if (get(AttributeFactoryType.class) == null
				|| get(AttributeFactoryType.class).getFactoryType() != FactoryType.SMELTING) {
			return;
		}
		// 通过description前缀判断origMachine是否为我们的离心机工厂
		// 防御性检查：getDescription() 可能返回 null
		var description = origMachine.getDescription();
		if (description == null) {
			return;
		}
		String desc = description.getTranslationKey();
		if (desc == null || !desc.startsWith("block." + ProductiveBeesGenesis.MOD_ID + ".")) {
			return;
		}
		// 添加EMExtraAttributeUpgradeable指向ABSOLUTE_OVERCLOCKED离心机工厂
		RegistryObject<?> blockHolder = RegistryObject.create(new ResourceLocation(ProductiveBeesGenesis.MOD_ID,
						"absolute_overclocked_emextra_mek_centrifuge_factory"), Registries.BLOCK, ProductiveBeesGenesis.MOD_ID);
		// 防御性检查：blockHolder.getKey() 在注册表尚未完成初始化时可能返回 null
		var key = blockHolder.getKey();
		if (key == null) return;
		RegistryObject<?> itemHolder = RegistryObject.create(
				key.location(), net.minecraft.core.registries.Registries.ITEM, ProductiveBeesGenesis.MOD_ID);
		@SuppressWarnings("unchecked")
		Supplier<BlockRegistryObject<?, ?>> broSupplier = () -> new BlockRegistryObject<>(
				(RegistryObject<Block>) blockHolder, (RegistryObject<Item>) itemHolder);
		this.add(new EMExtraAttributeUpgradeable(broSupplier));
	}
}
