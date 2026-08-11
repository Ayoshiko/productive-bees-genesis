package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
	 * 客户端 → 服务端：万象创世过滤配置同步数据包（Task 12）
	 * <p>
	 * 多人游戏下客户端无法直接修改 SERVER 配置 — 客户端的 SERVER 配置只是
	 * NeoForge 在配置阶段下发的只读同步副本。直接 {@code ConfigValue.set()} 仅修改
	 * 客户端本地副本，不会同步到服务端，下次同步还会被覆盖。
	 * <p>
	 * 此数据包将 {@code FilterListScreen} 的编辑结果发送到服务端，由服务端校验权限与
	 * 数据后写入 SERVER 配置并持久化，再由 NeoForge 原生 {@code ConfigSync} 机制
	 * 自动将变更同步到所有客户端（包括发起者）。
	 * <p>
	 * 数据量：服务端 bound 数据包上限 32 KiB，蜜蜂类型 ID 最长 256 字符，
	 * 实际承载能力远超需求（列表上限 512 条）。
	 * <p>
	 * <b>输入校验</b>：单条字符串长度上限 {@value #MAX_STRING_LENGTH} 字符，
	 * 超长时解码抛出异常，由网络层拒绝该数据包，防止恶意客户端发送超长字符串导致 OOM。
	 *
	 * @param filterModeName 过滤模式枚举名称（DISABLED/BLACKLIST/WHITELIST）
	 * @param beeTypes       蜜蜂类型 ID 列表（不可变拷贝）
	 */
public record FilterConfigSyncPayload(
		String filterModeName,
		List<String> beeTypes
) implements CustomPacketPayload {

	/** 数据包类型标识（命名空间:productivebeesgenesis, 路径:filter_config_sync） */
	public static final CustomPacketPayload.Type<FilterConfigSyncPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "filter_config_sync"));

	/** 蜜蜂类型列表上限 — 防止恶意/异常数据包导致 OOM */
	private static final int MAX_BEE_TYPES = 512;

	/** 单条字符串长度上限 — 防止恶意客户端发送超长字符串导致 OOM */
	private static final int MAX_STRING_LENGTH = 256;

	/**
	 * 流编解码器：过滤模式名称（String）+ 蜜蜂类型列表（List&lt;String&gt;）
	 * <p>
	 * 列表使用 {@code ByteBufCodecs.list(MAX_BEE_TYPES)} 限制解码上限，
	 * 超过 512 条时解码抛出异常，由网络层拒绝该数据包。
	 * <p>
	 * 单条字符串使用 {@code ByteBufCodecs.stringUtf8(MAX_STRING_LENGTH)} 限制解码上限，
	 * 超过 {@value #MAX_STRING_LENGTH} 字符时解码抛出异常。
	 */
	public static final StreamCodec<ByteBuf, FilterConfigSyncPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.stringUtf8(MAX_STRING_LENGTH), FilterConfigSyncPayload::filterModeName,
					ByteBufCodecs.stringUtf8(MAX_STRING_LENGTH).apply(ByteBufCodecs.list(MAX_BEE_TYPES)),
					FilterConfigSyncPayload::beeTypes,
					FilterConfigSyncPayload::new
			);

	/**
	 * 紧凑构造器 — 防御性不可变拷贝
	 * <p>
	 * 保证 {@code beeTypes} 在数据包生命周期内不可变，避免服务端处理时
	 * 被意外修改导致编解码不一致。
	 */
	public FilterConfigSyncPayload {
		beeTypes = List.copyOf(beeTypes);
	}

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
