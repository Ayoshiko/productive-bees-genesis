package com.ayoshiko.productivebeesgenesis.client.jade;

import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.entity.BlockEntity;

import snownee.jade.api.BlockAccessor;
import snownee.jade.api.IBlockComponentProvider;
import snownee.jade.api.IServerDataProvider;
import snownee.jade.api.ITooltip;
import snownee.jade.api.config.IPluginConfig;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2GridNodeManager;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHost;

/**
 * Jade AE2 网络状态显示组件
 * <br/>
 * 完全复刻 AE2 原版 {@code GridNodeStateDataProvider} 的显示设计，
 * 使用 AE2 原版翻译键和 {@link ChatFormatting#GRAY} 颜色：
 * <ul>
 *   <li>waila.ae2.DeviceOffline — 设备离线</li>
 *   <li>waila.ae2.NetworkBooting — 网络加载中</li>
 *   <li>waila.ae2.DeviceMissingChannel — 设备缺少频道</li>
 *   <li>waila.ae2.DeviceOnline — 设备在线</li>
 * </ul>
 * <p>
 * <b>数据同步</b>：服务端通过 {@link #appendServerData} 将状态 ordinal 写入 NBT（与 AE2 一致），
 * 客户端通过 {@link #appendTooltip} 读取并显示。避免客户端直接引用 AE2 类。
 * <p>
 * <b>显示条件</b>：仅当方块实体实现 {@link IAe2OutputHost} 且 AE2 集成已启用时显示。
 */
public final class JadeAe2StatusProvider
		implements IBlockComponentProvider, IServerDataProvider<BlockAccessor> {

	/** 无状态单例 — 服务端和客户端共用同一实例 */
	static final JadeAe2StatusProvider INSTANCE = new JadeAe2StatusProvider();

	/** NBT 键：AE2 节点状态 ordinal（与 AE2 原版 GridNodeState 一致），-1 = 不显示 */
	private static final String NBT_STATE = "pbg_grid_node_state";

	/** AE2 原版翻译键 */
	private static final String KEY_DEVICE_OFFLINE = "waila.ae2.DeviceOffline";
	private static final String KEY_NETWORK_BOOTING = "waila.ae2.NetworkBooting";
	private static final String KEY_MISSING_CHANNEL = "waila.ae2.DeviceMissingChannel";
	private static final String KEY_DEVICE_ONLINE = "waila.ae2.DeviceOnline";

	/** 插件唯一 ID — 用于 Jade 配置页面切换开关 */
	static final ResourceLocation UID =
			ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "ae2_status");

	@Override
	public ResourceLocation getUid() {
		return UID;
	}

	// ===== 服务端数据同步 =====

	@Override
	public void appendServerData(CompoundTag tag, BlockAccessor accessor) {
		BlockEntity be = accessor.getBlockEntity();
		if (!(be instanceof IAe2OutputHost host)) {
			tag.putByte(NBT_STATE, (byte) -1);
			return;
		}
		// 节点对象为空说明 AE2 集成未启用，不显示
		if (host.productivebeesgenesis$getAe2GridNode() == null) {
			tag.putByte(NBT_STATE, (byte) -1);
			return;
		}
		// 写入状态 ordinal（0-3），与 AE2 GridNodeState 一致
		tag.putByte(NBT_STATE, (byte) Ae2GridNodeManager.getGridNodeState(host));
	}

	// ===== 客户端显示 =====

	@Override
	public void appendTooltip(ITooltip tooltip, BlockAccessor accessor, IPluginConfig config) {
		CompoundTag serverData = accessor.getServerData();
		byte state = serverData.getByte(NBT_STATE);
		if (state < 0) return; // 不显示

		// 使用 AE2 原版翻译键 + GRAY 颜色（与 AE2 GridNodeStateDataProvider 完全一致）
		Component text = switch (state) {
			case 1 -> Component.translatable(KEY_NETWORK_BOOTING);
			case 2 -> Component.translatable(KEY_MISSING_CHANNEL);
			case 3 -> Component.translatable(KEY_DEVICE_ONLINE);
			default -> Component.translatable(KEY_DEVICE_OFFLINE);
		};
		tooltip.add(text.copy().withStyle(ChatFormatting.GRAY));
	}
}
