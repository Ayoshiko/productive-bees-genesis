package com.ayoshiko.productivebeesgenesis.mixin.mek;

import com.ayoshiko.productivebeesgenesis.apiary.CentrifugeUpgradeDataHelper;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.IMekCentrifugeTile;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import mekanism.common.tile.interfaces.ITierUpgradable;
import mekanism.common.upgrade.IUpgradeData;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

/** 三种安装器取得升级数据前拒收；此时源槽、资源所有权及世界方块均未改变。 */
@Pseudo
@Mixin(targets = {
		"mekanism.common.item.ItemTierInstaller",
		"com.jerry.mekextras.common.item.ItemExtraTierInstaller",
		"io.github.masyumero.emextras.common.item.EMExtraItemTierInstaller"
}, remap = false)
public abstract class ResourceUpgradeGuardMixin {
	@WrapOperation(method = "useOn", at = @At(value = "INVOKE",
			target = "Lmekanism/common/tile/interfaces/ITierUpgradable;getUpgradeData(Lnet/minecraft/core/HolderLookup$Provider;)Lmekanism/common/upgrade/IUpgradeData;"),
			require = 1)
	private IUpgradeData productivebeesgenesis$guardUpgrade(ITierUpgradable upgradable,
			HolderLookup.Provider provider, Operation<IUpgradeData> original,
			@Local(index = 9) BlockState target) {
		if (upgradable instanceof BlockEntity source
				&& (source instanceof TileEntityMekApiary || source instanceof IMekCentrifugeTile)) {
			if (!ResourceHiveHost.canUpgrade(source) || !CentrifugeUpgradeDataHelper.canInstallTier(source, target)) return null;
		}
		return original.call(upgradable, provider);
	}
}
