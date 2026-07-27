package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：桶式蜂笼操作数据包
 * <br/>
 * 玩家手持蜂笼右键点击蜜蜂槽位时发送，服务端根据操作类型执行取出或放入。
 * 与现有"放入蜂笼自动处理"和"点击选中"机制并存，提供即时精准操作。
 * <p>
 * 操作类型：
 * <ul>
 *   <li>{@link OperationType#EXTRACT} — 手持空蜂笼 + 选中格子有蜜蜂 → 取出蜜蜂到蜂笼</li>
 *   <li>{@link OperationType#INSERT} — 手持装有蜜蜂的蜂笼 + 选中格子为空 → 放入蜜蜂到格子</li>
 * </ul>
 * <p>
 * 安全性：服务端校验方块实体类型与玩家距离（标准 8 格 GUI 交互距离）。
 *
 * @param pos       蜂箱方块坐标
 * @param slotIndex 蜜蜂槽位索引（0~beeSlotCount-1）
 * @param operation 操作类型（EXTRACT 或 INSERT）
 */
public record ApiaryCageOperationPayload(
		BlockPos pos,
		int slotIndex,
		OperationType operation
) implements CustomPacketPayload {

	/** 操作类型枚举 */
	public enum OperationType {
		/** 取出：空蜂笼 → 从选中格子取出蜜蜂 */
		EXTRACT,
		/** 放入：含蜜蜂的蜂笼 → 放入到空格子 */
		INSERT;

		private static final OperationType[] VALUES = values();

		/** 通过 ordinal 查找枚举（带边界保护） */
		public static OperationType fromOrdinal(int ordinal) {
			return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : EXTRACT;
		}
	}

	/** 数据包类型标识 */
	public static final CustomPacketPayload.Type<ApiaryCageOperationPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "apiary_cage_operation"));

	/** 流编解码器 — 编解码 BlockPos + int + OperationType */
	public static final StreamCodec<ByteBuf, ApiaryCageOperationPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, ApiaryCageOperationPayload::pos,
					ByteBufCodecs.INT, ApiaryCageOperationPayload::slotIndex,
					ByteBufCodecs.idMapper(OperationType::fromOrdinal, OperationType::ordinal),
					ApiaryCageOperationPayload::operation,
					ApiaryCageOperationPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
