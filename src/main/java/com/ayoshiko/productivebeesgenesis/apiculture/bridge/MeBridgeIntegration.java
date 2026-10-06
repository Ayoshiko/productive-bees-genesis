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
	private static final class Loaded {
		static void clear(net.minecraft.server.MinecraftServer server) { com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeMeCatalogue.clear(server); }
		static MeBridgeLink create(MeBridgeBlockEntity bridge, CompoundTag saved) {
			return new com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode(bridge, saved);
		}
		static void register(RegisterCapabilitiesEvent event) { com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.MeBridgeNode.register(event); }
	}
	private MeBridgeIntegration() { }
}
