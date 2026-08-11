package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.client.ClientDevModeState;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

import java.util.HashMap;
import java.util.Map;

/**
	 * 服务端 → 客户端：开发者模式状态同步包
	 * <p>
	 * 携带主开关状态和子功能开关映射表，由服务端在以下时机发送：
	 * <ol>
	 *   <li>玩家登录时（PlayerLoggedInEvent）— 推送当前状态给新加入的客户端</li>
	 *   <li>命令切换状态时广播 — 推送给所有在线玩家</li>
	 * </ol>
	 * 客户端接收后调用 {@link ClientDevModeState#update} 更新本地镜像状态，
	 * 供 {@code ModCreativeTabs} 读取以控制开发物品可见性。
	 *
	 * @param masterEnabled 主开关状态
	 * @param featureStates 子功能开关映射表（key=featureName, value=enabled）
	 */
public record DevModeStateSyncPacket(
		boolean masterEnabled,
		Map<String, Boolean> featureStates
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<DevModeStateSyncPacket> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "dev_mode_state_sync"));

	public static final StreamCodec<ByteBuf, DevModeStateSyncPacket> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.BOOL, DevModeStateSyncPacket::masterEnabled,
					// 限制feature name最大64字符（如"dev_items"很短），防止恶意超大字符串导致OOM
					ByteBufCodecs.map(size -> new HashMap<>(size), ByteBufCodecs.stringUtf8(64), ByteBufCodecs.BOOL, 1024),
					DevModeStateSyncPacket::featureStates,
					DevModeStateSyncPacket::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/**
	 * 客户端处理：接收服务端推送的开发者模式状态，更新本地镜像
	 * <br/>
	 * 由 {@code ModPayloads.register} 通过方法引用挂载到本包的 playToClient 注册。
	 * 调用 {@link ClientDevModeState#update} 后，{@code ModCreativeTabs} 下次刷新
	 * 创造模式物品栏时会读取最新状态控制开发物品可见性。
	 * <p>
	 * ClientDevModeState 是纯 Java 状态管理类，不引用任何 net.minecraft.client.* 客户端专用类，
	 * 因此服务端加载本类触发 ClientDevModeState 类初始化不会导致 ClassNotFoundException。
	 */
	public static void handleClient(DevModeStateSyncPacket payload, IPayloadContext context) {
		ClientDevModeState.update(payload.masterEnabled(), payload.featureStates());
	}
}
