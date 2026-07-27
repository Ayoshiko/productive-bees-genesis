package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MekCentrifugeMEBlockType;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeUpgradeable;
import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;

import mekanism.common.block.attribute.AttributeFactoryType;
import mekanism.common.content.blocktype.BlockType;
import mekanism.common.content.blocktype.Factory;
import mekanism.common.content.blocktype.FactoryType;
import mekanism.common.content.blocktype.Machine;
import mekanism.common.tier.FactoryTier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * 注入Mekanism的Factory构造器，为ULTIMATE离心机工厂添加ME升级链
 * <br/>
 * ME的MixinFactory（priority=1000）为所有ULTIMATE工厂添加ExtraAttributeUpgradeable
 * 指向ME原版ABSOLUTE电力熔炼炉工厂。本Mixin（priority=1100）在ME之后执行，
 * 当origMachine是我们的离心机工厂时（通过description前缀判断），移除ME注入的
 * ExtraAttributeUpgradeable（指向ME原版ABSOLUTE电力熔炼炉），替换为指向我们的
 * ABSOLUTE离心机工厂。
 * <p>
 * 这样ULTIMATE离心机工厂同时拥有：
 * - AttributeUpgradeable（Mekanism升级系统，由createFactoryBlockType设置，指向ABSOLUTE离心机）
 * - ExtraAttributeUpgradeable（ME升级系统，由本Mixin设置，指向ABSOLUTE离心机）
 * 使Mekanism的ItemTierInstaller和ME的ItemExtraTierInstaller都能正确升级。
 * <p>
 * 注意：当前我们的离心机工厂使用FactoryMachine基类，不经过Factory构造器，
 * 所以此Mixin在当前实现中不会被触发。保留此Mixin作为扩展点，若将来改用Factory基类则自动生效。
 */
@Mixin(value = Factory.class, remap = false, priority = 1100)
public abstract class FactoryForMEMixin extends BlockType {

	private FactoryForMEMixin() {
		super(null);
	}

	@Inject(method = "<init>", at = @At("RETURN"), remap = false)
	private void productivebeesgenesis$onInit(Supplier<?> tileEntityRegistrar, Supplier<?> containerRegistrar,
			Machine.FactoryMachine<?> origMachine, FactoryTier tier,
			CallbackInfo ci) {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
			return;
		}
		// 仅处理ULTIMATE等级
		if (tier != FactoryTier.ULTIMATE) {
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
		// 移除ME的MixinFactory注入的ExtraAttributeUpgradeable（指向ME原版ABSOLUTE电力熔炼炉）
		this.remove(ExtraAttributeUpgradeable.class);
		// 添加指向我们的ABSOLUTE离心机工厂的ExtraAttributeUpgradeable
		ExtraMachine.ExtraFactoryMachine<?> absoluteType = MekCentrifugeMEBlockType.getMEFactoryType(ExtraFactoryTier.ABSOLUTE);
		if (absoluteType != null) {
			ExtraAttributeUpgradeable upgradeable = absoluteType.get(ExtraAttributeUpgradeable.class);
			if (upgradeable != null) {
				this.add(upgradeable);
			}
		}
	}
}
