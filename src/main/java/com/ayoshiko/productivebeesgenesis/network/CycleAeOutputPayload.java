package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
	 * 客户端 → 服务端：切换指定方块实体的 per-tile AE2 物品/流体输出开关
	 * <br/>
	 * 携带 {@link BlockPos} 和输出类型（物品/流体），服务端校验玩家距离（8格）
	 * 和方块实体类型后执行切换。切换后通过 SyncableBoolean tracker 自动同步到客户端。
	 * <p>
	 * 设计原因：GUI 中点击 AE2 输出按钮时发送此包，服务端执行实际的状态切换
	 * 并持久化到 NBT，避免客户端直接修改造成的状态不一致。
	 *
	 * @param pos        方块坐标
	 * @param outputType 输出类型（物品/流体）
	 */
public record CycleAeOutputPayload(
		BlockPos pos,
		OutputType outputType
) implements CustomPacketPayload {

	public static final CustomPacketPayload.Type<CycleAeOutputPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "cycle_ae_output"));

	/** 枚举编解码器：使用 idMapper 按序数序列化 */
	public static final StreamCodec<ByteBuf, CycleAeOutputPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, CycleAeOutputPayload::pos,
					ByteBufCodecs.idMapper(OutputType::fromOrdinal, OutputType::ordinal),
					CycleAeOutputPayload::outputType,
					CycleAeOutputPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** 输出类型枚举 */
	public enum OutputType {
		/** 物品输出 */
		ITEM,
		/** 流体输出 */
		FLUID,
		APIARY_DIRECT,
		CENTRIFUGE_DIRECT;

		private static final OutputType[] VALUES = values();

		/**
		 * 通过 ordinal 查找枚举（带边界保护）。
		 * <p>
		 * 当网络传输的 ordinal 超出枚举值范围（如协议版本不一致或恶意构造的数据包）时，
		 * 回退到 {@link #ITEM} 而非抛出 {@link ArrayIndexOutOfBoundsException}，避免服务端崩溃。
		 *
		 * @param ordinal 枚举序号
		 * @return 对应的枚举值；越界时返回 {@link #ITEM}
		 */
		public static OutputType fromOrdinal(int ordinal) {
			return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : ITEM;
		}
	}
}
