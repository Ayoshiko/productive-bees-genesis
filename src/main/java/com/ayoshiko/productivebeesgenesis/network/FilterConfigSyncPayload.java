package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.UUID;

/**
	 * 客户端 → 服务端：万象创世过滤配置同步数据包
	 * <p>
	 * 多人游戏下客户端无法直接修改 SERVER 配置 — 客户端的 SERVER 配置只是
	 * NeoForge 在配置阶段下发的只读同步副本。直接 {@code ConfigValue.set()} 仅修改
	 * 客户端本地副本，不会同步到服务端，下次同步还会被覆盖。
	 * <p>
	 * 此数据包将 {@code DesignerFilterScreen} 的编辑结果发送到服务端，由服务端校验权限与
	 * 数据后写入 SERVER 配置并持久化，再由 NeoForge 原生 {@code ConfigSync} 机制
	 * 自动将变更同步到所有客户端（包括发起者）。
	 * <p>
	 * 数据量：服务端 bound 数据包上限 32 KiB，蜜蜂类型 ID 最长 256 字符，
	 * 列表另受完整 JSON 的 28 KiB 预算约束，客户端提交前先检查总量。
	 * <p>
	 * <b>输入校验</b>：单条字符串长度上限 {@value #MAX_STRING_LENGTH} 字符，
	 * 超长时解码抛出异常，由网络层拒绝该数据包，防止恶意客户端发送超长字符串导致 OOM。
	 *
	 * @param filterModeName 过滤模式枚举名称（DISABLED/BLACKLIST/WHITELIST）
	 * @param beeTypes       蜜蜂类型 ID 列表（不可变拷贝）
	 */
public record FilterConfigSyncPayload(
		String filterModeName,
		List<String> beeTypes,
		UUID request,
		String baseline,
		boolean save,
		int page,
		long generation
) {

    public static FilterConfigSyncPayload decode(net.minecraft.network.FriendlyByteBuf b) {
        return new FilterConfigSyncPayload(b.readUtf(256), PacketIO.readList(b, () -> b.readUtf(256), 512), b.readUUID(), b.readUtf(64), b.readBoolean(), b.readVarInt(), b.readLong());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf b) {
        b.writeUtf(filterModeName, 256);
        PacketIO.writeList(b, beeTypes, v -> b.writeUtf(v, 256), 512);
        b.writeUUID(request); b.writeUtf(baseline,64); b.writeBoolean(save); b.writeVarInt(page); b.writeLong(generation);
    }
	/**
	 * 紧凑构造器 — 防御性不可变拷贝
	 * <p>
	 * 保证 {@code beeTypes} 在数据包生命周期内不可变，避免服务端处理时
	 * 被意外修改导致编解码不一致。
	 */
	public FilterConfigSyncPayload {
		beeTypes = List.copyOf(beeTypes);
	}

	public FilterConfigSyncPayload(String mode, List<String> types) {
		this(mode, types, UUID.randomUUID(), "", true);
	}

	public FilterConfigSyncPayload(String mode, List<String> types, UUID request, String baseline, boolean save) {
		this(mode, types, request, baseline, save, -1, -1);
	}
}
