package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.jerry.mekextras.common.block.attribute.ExtraAttributeUpgradeable;
import com.jerry.mekextras.common.content.blocktype.ExtraFactory;
import com.jerry.mekextras.common.content.blocktype.ExtraMachine;
import com.jerry.mekextras.common.tier.ExtraFactoryTier;

import mekanism.common.block.attribute.AttributeFactoryType;
import mekanism.common.content.blocktype.BlockType;
import mekanism.common.content.blocktype.FactoryType;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * 注入ME的ExtraFactory构造器，覆盖离心机相关的升级链
 * <br/>
 * ME的ExtraFactory构造器为非最高等级添加ExtraAttributeUpgradeable指向ME原版下一级工厂。
 * 当FactoryType为SMELTING且origMachine是我们的离心机工厂时（通过description前缀判断），
 * 移除ME添加的ExtraAttributeUpgradeable（指向ME原版电力熔炼炉工厂）。
 * 升级属性的重新添加由MekCentrifugeBlockType.createMEFactoryBlockType()处理。
 * <p>
 * 注意：当前我们的离心机工厂使用ExtraFactoryMachine基类直接创建，不经过ExtraFactory构造器，
 * 所以此Mixin在当前实现中不会被触发。保留此Mixin作为扩展点，若将来改用ExtraFactory基类则自动生效。
 */
@Mixin(value = ExtraFactory.class, remap = false)
public abstract class ExtraFactoryMixin extends BlockType {

	private ExtraFactoryMixin() {
		super(null);
	}

	@Inject(method = "<init>", at = @At("RETURN"), remap = false)
	private void productivebeesgenesis$onInit(Supplier<?> tileEntityRegistrar, Supplier<?> containerRegistrar,
			ExtraMachine.ExtraFactoryMachine<?> origMachine,
			ExtraFactoryTier tier, CallbackInfo ci) {
		if (!MekCompatHooks.isMekanismExtrasLoaded()) {
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
		// 移除ExtraFactory构造器添加的ExtraAttributeUpgradeable
		this.remove(ExtraAttributeUpgradeable.class);
	}
}
