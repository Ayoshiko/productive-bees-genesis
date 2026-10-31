package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
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
)  {

	public SetAeInputFilterEntryPayload(BlockPos pos, Optional<ResourceLocation> beeType, boolean isBlock,
			int slotIndex, OperationType operation) {
		this(pos, beeType, Optional.empty(), isBlock, slotIndex, operation);
	}

	/**
	 * beeType 编解码器 — 显式限制 256 字节，防止恶意客户端发送超长字符串占用内存。
	 * <br/>
	 * 不直接使用 {@link ResourceLocation#STREAM_CODEC}（隐式 1MB 上限过大），
	 * 改用 {@link ByteBufCodecs#stringUtf8(int)} 限制 256 字节（ResourceLocation
	 * 由 namespace:path 组成，256 字节足够覆盖合法值），再通过 map 在 String 与
	 * ResourceLocation 间转换。
	 */

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

    public static SetAeInputFilterEntryPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SetAeInputFilterEntryPayload(buf.readBlockPos(), buf.readBoolean() ? java.util.Optional.ofNullable(ResourceLocation.tryParse(buf.readUtf(256))) : java.util.Optional.empty(), buf.readBoolean() ? java.util.Optional.ofNullable(buf.readUtf(4096)) : java.util.Optional.empty(), buf.readBoolean(), buf.readVarInt(), buf.readEnum(OperationType.class));
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeBoolean(beeType.isPresent()); beeType.ifPresent(v -> { buf.writeUtf(v.toString(), 256); });
        buf.writeBoolean(directKey.isPresent()); directKey.ifPresent(v -> { buf.writeUtf(v, 4096); });
        buf.writeBoolean(isBlock);
        buf.writeVarInt(slotIndex);
        buf.writeEnum(operation);
    }
}
