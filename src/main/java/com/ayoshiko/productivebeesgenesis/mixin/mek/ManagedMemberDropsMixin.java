package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberBinding;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** 强制破坏可能绕过玩家事件，掉落计算仍不得把被封存的源库存再实体化。 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class ManagedMemberDropsMixin {
	@Inject(method = "getDrops", at = @At("HEAD"), cancellable = true)
	private void pbg$ownedDrops(LootParams.Builder params, CallbackInfoReturnable<List<ItemStack>> callback) {
		if (MemberBinding.isolated(params.getOptionalParameter(LootContextParams.BLOCK_ENTITY))) callback.setReturnValue(List.of());
	}
}
