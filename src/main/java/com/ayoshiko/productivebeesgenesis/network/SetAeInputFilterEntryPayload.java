package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Optional;

/**
	 * 客户端 → 服务端：添加、移除或清空 per-tile AE2 输入过滤条目
	 * <br/>
	 * 携带 {@link BlockPos}、操作类型、目标 slotIndex 和蜜蜂类型信息，
	 * 服务端校验后通过 {@code productivebeesgenesis$getAeInputFilter()} 获取过滤器执行操作。
	 * <p>
	 * <b>V13 变更</b>：添加 slotIndex（位置固定模式）和 isBlock（精确模式区分蜜脾/蜜脾块）。
	 * <ul>
	 *   <li>ADD：在 slotIndex 位置放置条目（beeType + isBlock）</li>
	 *   <li>REMOVE：移除 slotIndex 位置的条目</li>
	 *   <li>CLEAR：清空所有条目</li>
	 *   <li>TOGGLE_UNLIMITED：切换直连条目的无限提供状态</li>
	 *   <li>TOGGLE_NETWORK_STOCK：切换直连条目的库存模式</li>
	 * </ul>
	 *
	 * @param pos       方块坐标
	 * @param beeType   蜜蜂类型 ID（CLEAR/REMOVE 时可为 empty）
	 * @param isBlock   是否为蜜脾块（仅 ADD 时有意义）
	 * @param slotIndex 目标 slot 位置（0-based，仅 ADD/REMOVE 时有意义）
	 * @param operation 操作类型（ADD/REMOVE/CLEAR）
	 */
public record SetAeInputFilterEntryPayload(
		BlockPos pos,
		Optional<ResourceLocation> beeType,
		Optional<String> directKey,
		boolean isBlock,
		int slotIndex,
		OperationType operation
) implements CustomPacketPayload {

	public SetAeInputFilterEntryPayload(BlockPos pos, Optional<ResourceLocation> beeType, boolean isBlock,
			int slotIndex, OperationType operation) {
		this(pos, beeType, Optional.empty(), isBlock, slotIndex, operation);
	}

	public static final CustomPacketPayload.Type<SetAeInputFilterEntryPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "set_ae_input_filter_entry"));

	/**
	 * beeType 编解码器 — 显式限制 256 字节，防止恶意客户端发送超长字符串占用内存。
	 * <br/>
	 * 不直接使用 {@link ResourceLocation#STREAM_CODEC}（隐式 1MB 上限过大），
	 * 改用 {@link ByteBufCodecs#stringUtf8(int)} 限制 256 字节（ResourceLocation
	 * 由 namespace:path 组成，256 字节足够覆盖合法值），再通过 map 在 String 与
	 * ResourceLocation 间转换。
	 */
	private static final StreamCodec<ByteBuf, ResourceLocation> BEE_TYPE_CODEC =
			ByteBufCodecs.stringUtf8(256).map(
					// tryParse 而非 parse：非法字符串返回 null（由 Optional 包装为空），
					// 避免解码阶段抛 ResourceLocationException 导致网络层断开连接
					str -> ResourceLocation.tryParse(str),
					rl -> rl.toString()
			);

	public static final StreamCodec<ByteBuf, SetAeInputFilterEntryPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, SetAeInputFilterEntryPayload::pos,
					ByteBufCodecs.optional(BEE_TYPE_CODEC), SetAeInputFilterEntryPayload::beeType,
					ByteBufCodecs.optional(ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_AE_ITEM_FINGERPRINT_LENGTH)),
					SetAeInputFilterEntryPayload::directKey,
					ByteBufCodecs.BOOL, SetAeInputFilterEntryPayload::isBlock,
					ByteBufCodecs.INT, SetAeInputFilterEntryPayload::slotIndex,
					ByteBufCodecs.idMapper(OperationType::fromOrdinal, OperationType::ordinal),
					SetAeInputFilterEntryPayload::operation,
					SetAeInputFilterEntryPayload::new
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}

	/** 操作类型枚举 */
	public enum OperationType {
		/** 在 slotIndex 位置放置条目 */
		ADD,
		/** 移除 slotIndex 位置的条目 */
		REMOVE,
		/** 清空所有过滤条目 */
		CLEAR,
		/** 切换指定直连条目的无限提供状态 */
		TOGGLE_UNLIMITED,
		/** 切换指定直连条目的 AE2 库存保留模式 */
		TOGGLE_NETWORK_STOCK;

		private static final OperationType[] VALUES = values();

		/**
		 * 通过 ordinal 查找枚举（带边界保护）。
		 * <p>
		 * 当网络传输的 ordinal 超出枚举值范围（如协议版本不一致或恶意构造的数据包）时，
		 * 回退到 {@link #ADD} 而非抛出 {@link ArrayIndexOutOfBoundsException}，避免服务端崩溃。
		 *
		 * @param ordinal 枚举序号
		 * @return 对应的枚举值；越界时返回 {@link #ADD}
		 */
		public static OperationType fromOrdinal(int ordinal) {
			return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : ADD;
		}
	}
}
