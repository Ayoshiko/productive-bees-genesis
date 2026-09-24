package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 客户端点击场景的服务端资产夹具和独立守恒断言；不从客户端线程访问世界。 */
final class ClientTerminalFixture {
	static volatile boolean requested, ready, done, verified;
	private static ProductKey iron, red, blue, water;
	private static CompoundTag beeData;
	static void tick(NetworkCoreBlockEntity core, ServerPlayer player) {
		if (!requested || verified) return;
		var data = core.ownership().readyAuthority(); if (data == null) return;
		var current = data.checkpoint();
		if (!ready) {
			if (core.productionRunning() || current.ownedMachines().values().stream().noneMatch(r -> r.bees() != null)) return;
			iron = ProductKeyCodec.item(new ItemStack(Items.IRON_INGOT), player.registryAccess());
			var decorated = new ItemStack(Items.IRON_INGOT); decorated.set(DataComponents.CUSTOM_NAME, Component.literal("variant-red"));
			red = ProductKeyCodec.item(decorated, player.registryAccess());
			decorated.set(DataComponents.CUSTOM_NAME, Component.literal("variant-blue")); blue = ProductKeyCodec.item(decorated, player.registryAccess());
			water = ProductKeyCodec.fluid(new FluidStack(Fluids.WATER, 1), player.registryAccess());
			var stock = new ConcurrentHashMap<ProductKey, ProductAmount>();
			stock.put(iron, ProductAmount.of(65)); stock.put(red, ProductAmount.of(2)); stock.put(blue, ProductAmount.of(3)); stock.put(water, ProductAmount.of(2000));
			for (int i = 0; i < 9; i++) {
				decorated.set(DataComponents.CUSTOM_NAME, Component.literal("filler-" + i));
				stock.put(ProductKeyCodec.item(decorated, player.registryAccess()), ProductAmount.of(1));
			}
			com.ayoshiko.productivebeesgenesis.apiculture.persistence.ClientTerminalStockFixture.seed(data, stock);
			for (int i = 0; i < 36; i++) player.getInventory().setItem(i, new ItemStack(Items.STONE, 64));
			player.getInventory().setItem(0, new ItemStack(Items.IRON_BLOCK, 64));
			beeData = new CompoundTag(); beeData.putString("entity", "productivebees:configurable_bee");
			beeData.putString("type", "productivebees:iron"); beeData.putUUID("UUID", UUID.randomUUID());
			var cage = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.STURDY_BEE_CAGE.get()); cage.set(DataComponents.CUSTOM_DATA, CustomData.of(beeData));
			player.getInventory().setItem(1, cage); player.getInventory().setItem(2, new ItemStack(Items.BUCKET));
			player.getInventory().setItem(3, new ItemStack(Items.DIAMOND, 64));
			player.getInventory().setItem(4, new ItemStack(Items.IRON_INGOT, 63)); player.getInventory().setItem(5, ItemStack.EMPTY);
			player.getInventory().setChanged(); player.containerMenu.broadcastChanges(); ready = true;
		} else if (done) {
			require(current.ledger().available(iron).equals(ProductAmount.of(64)), "Client partial item transfer changed wrong amount");
			require(current.ledger().available(red).isZero() && current.ledger().available(blue).equals(ProductAmount.of(3)), "Client confused component variants");
			require(current.ledger().available(water).equals(ProductAmount.of(1000)), "Client bucket transfer changed wrong amount");
			require(player.getInventory().getItem(0).getCount() == 64, "Client food round trip lost items");
			require(beeData.equals(VerifiedCageProjection.contents(player.getInventory().getItem(1))), "Client cage round trip changed entity data");
			require(player.getInventory().getItem(2).is(Items.WATER_BUCKET) && player.getInventory().getItem(3).getCount() == 64
					&& player.getInventory().getItem(4).getCount() == 64 && player.getInventory().getItem(5).getCount() == 2, "Client inventory conservation failed");
			for (var record : current.ownedMachines().activeValues()) if (record.bees() != null) {
				require(record.bees().bees().isEmpty() && record.bees().feeding().slots().stream().allMatch(s -> s.count() == 0), "Client left duplicate food or bee");
			}
			verified = true;
		}
	}
	private ClientTerminalFixture() { }
}
