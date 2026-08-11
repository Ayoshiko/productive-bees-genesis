package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
	 * 客户端 → 服务端：通用机械蜂箱选中蜜蜂槽位数据包（Bug 9）
	 * <br/>
	 * 玩家在 GUI 中点击蜜蜂槽位以选中该蜜蜂，下次放入空蜂笼时优先取出选中槽位的蜜蜂。
	 * 携带 {@link BlockPos} 和槽位索引（-1 表示取消选择），服务端收到后调用
	 * {@code TileEntityMekApiary.setSelectedBeeSlot(int)} 更新选中状态并同步给所有客户端。
	 * <p>
	 * 设计原因：原版取蜜蜂按槽位顺序，高等级工厂蜜蜂数量多时用户难以取走特定格子的蜜蜂。
	 * 引入"先选中再取走"机制：玩家点击选中目标蜜蜂，再放空蜂笼即可精准取出。
	 * <p>
	 * 安全性：服务端校验方块实体类型与玩家距离（标准 8 格 GUI 交互距离）。
	 *
	 * @param pos       蜂箱方块坐标
	 * @param slotIndex 蜜蜂槽位索引（0~beeSlotCount-1，或 -1 表示取消选择）
	 */
public record ApiarySelectBeePayload(
		BlockPos pos,
		int slotIndex
) implements CustomPacketPayload {

	/** 数据包类型标识 */
	public static final CustomPacketPayload.Type<ApiarySelectBeePayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "apiary_select_bee"));

	/** 流编解码器 — 编解码 BlockPos + int */
	public static final StreamCodec<ByteBuf, ApiarySelectBeePayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ApiarySelectBeePayload::pos,
					ByteBufCodecs.INT, ApiarySelectBeePayload::slotIndex,
					ApiarySelectBeePayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
