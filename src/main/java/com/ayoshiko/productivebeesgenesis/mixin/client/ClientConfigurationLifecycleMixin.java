package com.ayoshiko.productivebeesgenesis.mixin.client;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesisClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.client.multiplayer.ClientConfigurationPacketListenerImpl;
import net.minecraft.client.multiplayer.CommonListenerCookie;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 配置包早于玩家登录；配置握手失败也必须撤销该连接的镜像。 */
@Mixin(ClientConfigurationPacketListenerImpl.class)
public abstract class ClientConfigurationLifecycleMixin extends ClientCommonPacketListenerImpl {
	protected ClientConfigurationLifecycleMixin(Minecraft minecraft, Connection connection, CommonListenerCookie cookie) {
		super(minecraft, connection, cookie);
	}

	@Inject(method = "<init>", at = @At("RETURN"))
	private void productivebeesgenesis$beginConnection(
			Minecraft minecraft, Connection connection, CommonListenerCookie cookie, CallbackInfo ci) {
		ProductiveBeesGenesisClient.WorldCacheLifecycle.beginConnection(minecraft, connection);
	}

	@Inject(method = "onDisconnect", at = @At("HEAD"))
	private void productivebeesgenesis$endConnection(DisconnectionDetails details, CallbackInfo ci) {
		ProductiveBeesGenesisClient.WorldCacheLifecycle.endConnection(this.connection);
	}
}
