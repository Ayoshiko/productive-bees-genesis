package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.List;

/**
	 * 服务端 → 客户端：同步 per-tile AE2 输入过滤器的完整条目列表
	 * <br/>
	 * 携带 {@link BlockPos}、过滤模式 ordinal、精确模式标志和非空条目列表（含位置索引）。
	 * <p>
	 * <b>V15 变更</b>：
	 * <ul>
	 *   <li>从 {@code List<String> entries} 改为 {@code List<Integer> indices} +
	 *       {@code List<String> entries} 两个并行数组，携带槽位位置信息</li>
	 *   <li>仅同步非空槽位，客户端按 index 写入固定大小数组，保留位置固定语义</li>
	 * </ul>
	 * <p>
	 * <b>V13 变更</b>：
	 * <ul>
	 *   <li>entries 从 {@code List<ResourceLocation>} 改为 {@code List<String>}，
	 *       支持 #block 后缀（精确模式下区分蜜脾和蜜脾块）</li>
	 *   <li>新增 preciseMode 字段，同步精确模式开关状态</li>
	 * </ul>
	 * <p>
	 * <b>设计原因</b>：Mekanism 的 {@code SyncableInt}/{@code SyncableBoolean} 等 container tracker
	 * 仅支持原子类型同步，无法同步集合数据。故服务端在条目增删、模式切换、GUI 打开时推送此包。
	 *
	 * @param pos         方块坐标
	 * @param filterMode  过滤模式 ordinal（0=DISABLED, 1=WHITELIST, 2=BLACKLIST）
	 * @param preciseMode 精确模式（true=区分蜜脾/蜜脾块）
	 * @param indices     非空槽位的 index 列表（与 entries 平行）
	 * @param entries     非空槽位的 entry 字符串列表（与 indices 平行，可能含 #block 后缀）
	 * @param amounts     直连条目的 AE2 实时库存数量（与 entries 平行，非直连条目为 0）
	 * @param unlimited    直连条目的无限提供状态（与 entries 平行，非直连条目为 false）
	 */
public record SyncAeInputFilterEntriesPayload(
		BlockPos pos,
		int filterMode,
		boolean preciseMode,
		List<Integer> indices,
		List<String> entries,
		List<Long> amounts,
		List<Long> visibleAmounts,
		List<Boolean> unlimited,
		boolean unlimitedAllFallback
) implements CustomPacketPayload {

	/** Backward-compatible constructor for callers that do not provide stock metadata. */
	public SyncAeInputFilterEntriesPayload(BlockPos pos, int filterMode, boolean preciseMode,
			List<Integer> indices, List<String> entries) {
		this(pos, filterMode, preciseMode, indices, entries,
				Collections.nCopies(entries.size(), 0L),
				Collections.nCopies(entries.size(), 0L),
				Collections.nCopies(entries.size(), false), false);
	}

	/**
	 * StreamCodec.composite supports at most six fields in this NeoForge version.
	 * Keep the public payload fields unchanged while encoding the two per-entry
	 * metadata lists as one composite field.
	 */
	private record DirectState(List<Long> amounts, List<Long> visibleAmounts, List<Boolean> unlimited, boolean unlimitedAllFallback) {}

	private static final StreamCodec<ByteBuf, DirectState> DIRECT_STATE_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(1024)), DirectState::amounts,
					ByteBufCodecs.VAR_LONG.apply(ByteBufCodecs.list(1024)), DirectState::visibleAmounts,
					ByteBufCodecs.BOOL.apply(ByteBufCodecs.list(1024)), DirectState::unlimited,
					ByteBufCodecs.BOOL, DirectState::unlimitedAllFallback,
					DirectState::new
			);

	public static final CustomPacketPayload.Type<SyncAeInputFilterEntriesPayload> TYPE =
			new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
					ProductiveBeesGenesis.MOD_ID, "sync_ae_input_filter_entries"));

	public static final StreamCodec<ByteBuf, SyncAeInputFilterEntriesPayload> STREAM_CODEC =
			StreamCodec.composite(
					BlockPos.STREAM_CODEC, SyncAeInputFilterEntriesPayload::pos,
					ByteBufCodecs.VAR_INT, SyncAeInputFilterEntriesPayload::filterMode,
					ByteBufCodecs.BOOL, SyncAeInputFilterEntriesPayload::preciseMode,
					ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list(1024)), SyncAeInputFilterEntriesPayload::indices,
					// 限制单个entry最大256字符（beeType ResourceLocation足够），防止恶意超大字符串导致OOM
					ByteBufCodecs.stringUtf8(NetworkSecurityConstants.MAX_FILTER_ENTRY_LENGTH)
							.apply(ByteBufCodecs.list(1024)), SyncAeInputFilterEntriesPayload::entries,
					DIRECT_STATE_CODEC,
					payload -> new DirectState(payload.amounts(), payload.visibleAmounts(), payload.unlimited(), payload.unlimitedAllFallback()),
					(pos, filterMode, preciseMode, indices, entries, directState) -> new SyncAeInputFilterEntriesPayload(
							pos, filterMode, preciseMode, indices, entries,
							directState.amounts(), directState.visibleAmounts(), directState.unlimited(), directState.unlimitedAllFallback())
			);

	@Override
	public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}
