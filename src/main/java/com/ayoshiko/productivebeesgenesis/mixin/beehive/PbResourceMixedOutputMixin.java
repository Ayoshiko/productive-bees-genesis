package com.ayoshiko.productivebeesgenesis.mixin.beehive;

import com.ayoshiko.productivebeesgenesis.resource.PbResourceBridge;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceMixinContract;
import com.ayoshiko.productivebeesgenesis.resource.PbResourceProduction;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import cy.jdkdigital.productivebees.common.block.entity.AdvancedBeehiveBlockEntity;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.util.List;

/** 与根事务同组严格启用；取消事件与占巢更新完成前不写入混合物品。 */
@Mixin(value = AdvancedBeehiveBlockEntity.class, priority = 900)
public abstract class PbResourceMixedOutputMixin implements PbResourceBridge {
	@Unique
	@Override
	public boolean productivebeesgenesis$mixedBridgeReady() { return true; }

	@WrapOperation(method = PbResourceMixinContract.EVENT, at = @At(value = "INVOKE",
			target = PbResourceMixinContract.ITEM_PRODUCE), require = 1)
	private List<ItemStack> productivebeesgenesis$deferMixedItems(Level level, Bee bee, boolean blocks,
			double modifier, Operation<List<ItemStack>> original) {
		if (PbResourceProduction.deferItems((AdvancedBeehiveBlockEntity) (Object) this, bee)) return List.of();
		return original.call(level, bee, blocks, modifier);
	}
}
