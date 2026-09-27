package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiary.GeneTreatRestockState;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MachineAssetStore;
import com.ayoshiko.productivebeesgenesis.init.ModBlocks;
import com.google.gson.JsonObject;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneGroup;
import cy.jdkdigital.productivebees.util.GeneValue;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 维护版补货状态接入网络交接；使用真实机器与注册表，未知提取结果不进入冻结。 */
final class ApiaryOwnershipMergeProbe {
	static void verify(ServerLevel level, JsonObject report) {
		var pos = new BlockPos(12, 170, 12);
		level.setBlockAndUpdate(pos, ModBlocks.MEK_APIARY.get().defaultBlockState());
		var tile = (TileEntityMekApiary) level.getBlockEntity(pos);
		var assets = new MachineAssetStore(tile);
		var registries = level.registryAccess();
		var legacy = assets.capture(registries);
		require(!legacy.copy().getCompound("extra").contains(GeneTreatRestockState.NBT_KEY),
				"Default restock changed legacy asset fingerprint");
		assets.validate(legacy, level);
		assets.clear(registries);
		assets.restore(legacy, level);
		require(legacy.equals(assets.capture(registries)), "Legacy assets failed exact return");

		var state = tile.getGeneTreatRestock();
		var treat = new ItemStack(ModItems.HONEY_TREAT.get(), 12);
		treat.set(ModDataComponents.GENE_GROUP_LIST.get(), List.of(new GeneGroup(
				GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_VERY_HIGH.getSerializedName(), 100)));
		state.setEnabled(true); state.observe(treat);
		var configured = assets.capture(registries);
		assets.validate(configured, level);
		assets.clear(registries);
		require(assets.empty() && !state.isEnabled() && state.template().isEmpty(), "Managed source retained restock state");
		assets.restore(configured, level);
		require(configured.equals(assets.capture(registries)) && state.isEnabled()
				&& ItemStack.isSameItemSameComponents(treat, state.template()), "Restock settings lost on return");

		state.acceptExtracted(treat);
		var pending = state.save(registries);
		require(!assets.prepared(), "Pending restock admitted to network freeze");
		try { assets.capture(registries); throw new AssertionError("Pending restock captured"); }
		catch (IllegalStateException expected) { }
		require(pending.equals(state.save(registries)), "Rejected capture changed paid items");
		require(state.deliverPending(tile.getGeneTreatSlot()) && assets.prepared(), "Settled restock did not become admissible");
		var settled = assets.capture(registries); assets.validate(settled, level);
		assets.clear(registries); assets.restore(settled, level);
		require(tile.getGeneTreatSlot().getCount() == 12, "Settled gene treats lost on transfer");

		state.quarantineExtraction();
		var unknown = state.save(registries);
		require(!assets.prepared(), "Unknown extraction admitted");
		state.setEnabled(false); state.setEnabled(true);
		require(!assets.prepared() && state.isSuspended(), "Toggle cleared unknown extraction");
		state.load(unknown, registries);
		var invalid = new CompoundTag(); invalid.putInt("version", 99);
		state.load(invalid, registries);
		require(!assets.prepared() && invalid.equals(state.save(registries)), "Invalid restock data lost or admitted");

		// 夹具在方法结束后不保留机器或待交付物品；这不是生产路径的恢复动作。
		assets.clear(registries); level.removeBlock(pos, false);
		report.addProperty("maintenanceRestockOwnershipAndLegacyImage", true);
	}
	private ApiaryOwnershipMergeProbe() { }
}
