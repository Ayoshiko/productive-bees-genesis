package com.ayoshiko.productivebeesgenesis.apiculture.bridge;

import net.minecraft.nbt.CompoundTag;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;

public final class MeBridgeIntegration {
	public static boolean installed() { return ModList.get().isLoaded("ae2"); }
	public static MeBridgeLink create(MeBridgeBlockEntity bridge, CompoundTag saved) {
		return installed() ? Loaded.create(bridge, saved) : null;
	}
	public static void register(RegisterCapabilitiesEvent event) { if (installed()) Loaded.register(event); }
	public static void clearMeCache(net.minecraft.server.MinecraftServer server) { if (installed()) Loaded.clear(server); }
	public static void forgetCompletions(net.minecraft.server.level.ServerPlayer player) { if (installed()) Loaded.forget(player); }
	public static com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan scalePattern(net.minecraft.world.item.ItemStack item, long factor, boolean divide) {
		return installed() ? Loaded.scalePattern(item, factor, divide)
				: com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan.failed(com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.Status.PATTERN_UNSUPPORTED);
	}
	public static boolean blankPattern(net.minecraft.world.item.ItemStack item) { return installed() && Loaded.blankPattern(item); }
	public static com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan encodeCraftingPattern(net.minecraft.server.level.ServerPlayer player,
			net.minecraft.world.item.ItemStack item, com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSource source) {
		return installed() ? Loaded.encodeCraftingPattern(player, item, source)
				: com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan.failed(com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.Status.PATTERN_UNSUPPORTED);
	}
	private static final class Loaded {
		static boolean blankPattern(net.minecraft.world.item.ItemStack item) { return com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingPatternEncoder.isBlank(item); }
		static com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan encodeCraftingPattern(net.minecraft.server.level.ServerPlayer player,
				net.minecraft.world.item.ItemStack item, com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternSource source) {
			return com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingPatternEncoder.prepare(player, item, source);
		}
		static com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan scalePattern(net.minecraft.world.item.ItemStack item, long factor, boolean divide) {
			return com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AePatternEditor.prepare(item, factor, divide);
		}
		static void clear(net.minecraft.server.MinecraftServer server) {
			com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeMeCatalogue.clear(server);
			com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingCompletions.clear(server);
		}
		static void forget(net.minecraft.server.level.ServerPlayer player) { com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingCompletions.forget(player); }
		static MeBridgeLink create(MeBridgeBlockEntity bridge, CompoundTag saved) {
			return new com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode(bridge, saved);
		}
		static void register(RegisterCapabilitiesEvent event) { com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode.register(event); }
	}
	private MeBridgeIntegration() { }
}
