package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
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
	 * @param amounts     直连条目的每次拉取数量（与 entries 平行，非直连条目为 0）
	 * @param reserveAmounts 直连条目的网络库存保留量（与 entries 平行，非直连条目为 0）
	 * @param unlimited    直连条目的无限提供状态（与 entries 平行，非直连条目为 false）
	 * @param networkStock 直连条目的库存模式状态（与 entries 平行，非直连条目为 false）
 * @param processable  该条目配置的物品本机能否加工（与 entries 平行，仅用于界面灰显提示）
	 * @param globalNetworkStock 是否对所有过滤器允许的蜜脾应用默认库存保留
	 * @param globalReserveAmount 过滤器级默认库存保留量
	 */
public record SyncAeInputFilterEntriesPayload(
		BlockPos pos,
		int filterMode,
		boolean preciseMode,
		List<Integer> indices,
	List<String> entries,
	List<Long> amounts,
	List<Long> reserveAmounts,
	List<Long> visibleAmounts,
	List<Boolean> unlimited,
	List<Boolean> networkStock,
	List<Boolean> processable,
	boolean unlimitedAllFallback,
	boolean globalNetworkStock,
	long globalReserveAmount
)  {

	/** Backward-compatible constructor for callers that do not provide stock metadata. */
	public SyncAeInputFilterEntriesPayload(BlockPos pos, int filterMode, boolean preciseMode,
			List<Integer> indices, List<String> entries) {
		this(pos, filterMode, preciseMode, indices, entries,
				Collections.nCopies(entries.size(), 0L),
				Collections.nCopies(entries.size(), 0L),
				Collections.nCopies(entries.size(), 0L),
				Collections.nCopies(entries.size(), false),
				Collections.nCopies(entries.size(), false),
				Collections.nCopies(entries.size(), true), false, false, 0L);
	}

	/**
	 * StreamCodec.composite supports at most six fields in this NeoForge version.
	 * Encode the per-entry metadata lists as one composite field.
	 */
	/**
	 * 每条目「本机可加工」标记与三个全局默认值打包在同一复合字段里。
	 * <p>
	 * 原因：{@code StreamCodec.composite} 在本 NeoForge 版本最多六个字段，
	 * {@link DirectState} 已经用满，故把这份展示态标记并入 StockDefaults 这一层。
	 */

    public static SyncAeInputFilterEntriesPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SyncAeInputFilterEntriesPayload(buf.readBlockPos(), buf.readVarInt(), buf.readBoolean(), PacketIO.readList(buf, () -> buf.readVarInt(), 1024), PacketIO.readList(buf, () -> buf.readUtf(4097), 1024), PacketIO.readList(buf, () -> buf.readVarLong(), 1024), PacketIO.readList(buf, () -> buf.readVarLong(), 1024), PacketIO.readList(buf, () -> buf.readVarLong(), 1024), PacketIO.readList(buf, () -> buf.readBoolean(), 1024), PacketIO.readList(buf, () -> buf.readBoolean(), 1024), PacketIO.readList(buf, () -> buf.readBoolean(), 1024), buf.readBoolean(), buf.readBoolean(), buf.readVarLong());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeVarInt(filterMode);
        buf.writeBoolean(preciseMode);
        PacketIO.writeList(buf, indices, v -> { buf.writeVarInt(v); }, 1024);
        PacketIO.writeList(buf, entries, v -> { buf.writeUtf(v, 4097); }, 1024);
        PacketIO.writeList(buf, amounts, v -> { buf.writeVarLong(v); }, 1024);
        PacketIO.writeList(buf, reserveAmounts, v -> { buf.writeVarLong(v); }, 1024);
        PacketIO.writeList(buf, visibleAmounts, v -> { buf.writeVarLong(v); }, 1024);
        PacketIO.writeList(buf, unlimited, v -> { buf.writeBoolean(v); }, 1024);
        PacketIO.writeList(buf, networkStock, v -> { buf.writeBoolean(v); }, 1024);
        PacketIO.writeList(buf, processable, v -> { buf.writeBoolean(v); }, 1024);
        buf.writeBoolean(unlimitedAllFallback);
        buf.writeBoolean(globalNetworkStock);
        buf.writeVarLong(globalReserveAmount);
    }
}
