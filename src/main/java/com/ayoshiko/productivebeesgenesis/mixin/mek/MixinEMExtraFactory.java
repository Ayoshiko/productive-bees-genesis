package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;

import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeFactoryType;
import io.github.masyumero.emextras.common.block.attribute.EMExtraAttributeUpgradeable;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraFactory;
import io.github.masyumero.emextras.common.content.blocktype.EMExtraMachine;
import io.github.masyumero.emextras.common.tier.EMExtraFactoryTier;

import mekanism.common.content.blocktype.BlockType;
import mekanism.common.registration.impl.BlockRegistryObject;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.registries.DeferredHolder;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

/**
 * 注入EME的EMExtraFactory构造器，覆盖离心机相关的升级链
 * <br/>
 * EME的EMExtraFactory构造器为非最高等级添加EMExtraAttributeUpgradeable指向EME原版下一级工厂。
 * 当FactoryType为SMELTING且origMachine是我们的离心机工厂时（通过description前缀判断），
 * 移除EME添加的EMExtraAttributeUpgradeable（指向EME原版电力熔炼炉工厂），
 * 替换为指向我们的EME离心机工厂的升级属性。
 * <p>
 * 注意：当前我们的离心机工厂使用EMExtraFactoryMachine基类直接创建，不经过EMExtraFactory构造器，
 * 所以此Mixin在当前实现中不会被触发。保留此Mixin作为扩展点，若将来改用EMExtraFactory基类则自动生效。
 */
@Mixin(value = EMExtraFactory.class, remap = false, priority = 1100)
public abstract class MixinEMExtraFactory extends BlockType {

    private MixinEMExtraFactory() {
        super(null);
    }

    @Inject(method = "<init>", at = @At("RETURN"), remap = false)
    private void productivebeesgenesis$onInit(Supplier<?> tileEntityRegistrar, Supplier<?> containerRegistrar,
                                               EMExtraMachine.EMExtraFactoryMachine<?> origMachine,
                                               EMExtraFactoryTier tier, CallbackInfo ci) {
        if (!MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
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
        // 移除EMExtraFactory构造器添加的EMExtraAttributeUpgradeable（指向EME原版电力熔炼炉工厂）
        this.remove(EMExtraAttributeUpgradeable.class);
        // 添加指向我们的EME离心机工厂的EMExtraAttributeUpgradeable
        EMExtraFactoryTier[] tiers = EMExtraFactoryTier.values();
        if (tier.ordinal() < tiers.length - 1) {
            EMExtraFactoryTier nextTier = tiers[tier.ordinal() + 1];
            String nextTierName = nextTier.getEMExtraTier().getLowerName();
            DeferredHolder<Block, ?> blockHolder = DeferredHolder.create(Registries.BLOCK,
                    ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID,
                            nextTierName + "_emextra_mek_centrifuge_factory"));
            // 防御性检查：blockHolder.getKey() 在注册表尚未完成初始化时可能返回 null
            var key = blockHolder.getKey();
            if (key == null) return;
            DeferredHolder<Item, ?> itemHolder = DeferredHolder.create(
                    Registries.ITEM, key.location());
            @SuppressWarnings("unchecked")
            Supplier<BlockRegistryObject<?, ?>> broSupplier = () -> new BlockRegistryObject<>(
                    (DeferredHolder<Block, Block>) blockHolder, (DeferredHolder<Item, Item>) itemHolder);
            this.add(new EMExtraAttributeUpgradeable(broSupplier));
        }
    }
}
