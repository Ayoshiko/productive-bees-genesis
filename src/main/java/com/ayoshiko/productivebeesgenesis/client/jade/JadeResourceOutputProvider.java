package com.ayoshiko.productivebeesgenesis.client.jade;

import com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceOutputStatus;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import snownee.jade.api.*;
import snownee.jade.api.config.IPluginConfig;

/** 只读观察产物接口；资源余额以服务端有界快照为准。 */
public final class JadeResourceOutputProvider implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {
	static final JadeResourceOutputProvider INSTANCE = new JadeResourceOutputProvider();
	private static final String DATA = "productivebeesgenesis_resources";
	@Override public ResourceLocation getUid() { return ResourceLocation.parse("productivebeesgenesis:resource_output"); }
	@Override public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
		var host = accessor.getBlockEntity();
		if (host != null && ResourceHiveHost.existing(host) != null && accessor.getPlayer() instanceof ServerPlayer player) {
			tag.put(DATA, ResourceOutputStatus.capture(host, player).save());
		}
	}
	@Override public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		if (!accessor.getServerData().contains(DATA)) return;
		ResourceOutputStatus status;
		try { status = ResourceOutputStatus.load(accessor.getServerData().getCompound(DATA)); }
		catch (IllegalArgumentException invalid) { return; }
		tooltip.add(Component.translatable("resource.productivebeesgenesis.status." + status.state().name().toLowerCase(java.util.Locale.ROOT))
				.withStyle(status.state() == ResourceOutputStatus.State.PAUSED ? ChatFormatting.YELLOW : ChatFormatting.GRAY));
		if (status.state() == ResourceOutputStatus.State.PRIVATE || status.state() == ResourceOutputStatus.State.UNAVAILABLE) return;
		tooltip.add(Component.translatable("resource.productivebeesgenesis.me." + (status.meEnabled() ? "enabled" : "disabled")));
		for (var balance : status.balances()) {
			var quantity = ResourceVisuals.compactAmount(balance.key(), balance.exactAmount()) + " ";
			tooltip.add(ResourceVisuals.name(balance.key()).copy().append(": ")
					.append(quantity).append(ResourceVisuals.unit(balance.key())));
		}
		if (status.totalKinds() > status.balances().size()) tooltip.add(Component.translatable(
				"resource.productivebeesgenesis.more", status.totalKinds() - status.balances().size()));
	}
}
