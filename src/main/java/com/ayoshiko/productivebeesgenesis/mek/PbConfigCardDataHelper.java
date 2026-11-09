package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2NbtKeys;
import com.ayoshiko.productivebeesgenesis.config.BalanceConfig;
import com.ayoshiko.productivebeesgenesis.util.PlayerInventoryTransfer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

/**
	 * 配置卡数据复制工具 — PB升级和AE2 per-tile状态的序列化/反序列化
	 * <br/>
	 * MEK原版配置卡通过 {@code writeSustainedData}/{@code readSustainedData} 复制配置数据，
	 * 但模组未重写这两个方法，导致PB升级和AE2 per-tile状态无法通过配置卡复制。
	 * <p>
	 * 本工具类封装配置卡数据中PB升级和AE2状态的读写逻辑，供各机器的
	 * {@code writeSustainedData}/{@code readSustainedData} 调用。
	 * <p>
	 * <b>生存模式物品消耗</b>：粘贴时若玩家非创造模式，需要从玩家背包消耗对应数量的PB升级物品。
	 * 消耗失败（物品不足）的类型跳过安装，不影响其他类型。
	 * <p>
	 * 设计原则：单一职责（只处理配置卡数据复制）、开闭原则（新增机器类型只需调用本工具类）。
	 *
	 * @since 2.0.0
	 */
public final class PbConfigCardDataHelper {

	/** 配置卡数据中PB升级数量的NBT键 */
	private static final String NBT_KEY_PB_UPGRADES = "productivebeesgenesis_pb_upgrades";

	private PbConfigCardDataHelper() {
	}

	// ===== 写入配置卡数据 =====

	/**
	 * 将PB升级数量写入配置卡数据
	 *
	 * @param data     配置卡NBT（由MEK的getConfigurationData提供）
	 * @param counts   PB升级数量映射（key=PbUpgradeType, value=count）
	 * @param machineType 机器类型（用于确定支持的升级类型）
	 */
	public static void writePbUpgrades(@NotNull CompoundTag data,
			@NotNull Map<PbUpgradeType, Integer> counts,
			@NotNull MachineType machineType) {
		CompoundTag upgradesTag = new CompoundTag();
		for (Map.Entry<PbUpgradeType, Integer> entry : counts.entrySet()) {
			if (entry.getValue() > 0 && isSupported(entry.getKey(), machineType)) {
				upgradesTag.putInt(entry.getKey().getId(), entry.getValue());
			}
		}
		if (!upgradesTag.getAllKeys().isEmpty()) {
			data.put(NBT_KEY_PB_UPGRADES, upgradesTag);
		}
	}

	/**
	 * 将AE2 per-tile状态写入配置卡数据
	 *
	 * @param data             配置卡NBT
	 * @param aeItemOutput     物品输出开关
	 * @param aeFluidOutput    流体输出开关
	 */
	public static void writeAe2PerTileState(@NotNull CompoundTag data,
											boolean aeItemOutput, boolean aeFluidOutput) {
		data.putBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT, aeItemOutput);
		data.putBoolean(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT, aeFluidOutput);
	}

	// ===== 从配置卡读取并应用 =====

	/**
	 * 配置卡仅交换净增加／净卸下的升级；背包最终状态可完整保存后才提交数量。
	 * 已安装物品可直接复用，避免同一配置因临时返还需要额外空位。
	 */
	public static boolean readAndApplyPbUpgrades(@NotNull CompoundTag data,
			@Nullable Player player, @NotNull Map<PbUpgradeType, Integer> currentCounts,
			@NotNull ToIntFunction<PbUpgradeType> limit,
			@NotNull Consumer<Map<PbUpgradeType, Integer>> replaceCounts,
			@NotNull MachineType machineType) {
		if (!data.contains(NBT_KEY_PB_UPGRADES)) {
			return true;
		}
		if (player == null || player.isSpectator() || player.level().isClientSide()
				|| !data.contains(NBT_KEY_PB_UPGRADES, CompoundTag.TAG_COMPOUND)) {
			return false;
		}
		Map<PbUpgradeType, Integer> before = new EnumMap<>(PbUpgradeType.class);
		before.putAll(currentCounts);
		Map<PbUpgradeType, Integer> after = new EnumMap<>(PbUpgradeType.class);
		for (Map.Entry<PbUpgradeType, Integer> entry : before.entrySet()) {
			if (!isSupported(entry.getKey(), machineType)) {
				after.put(entry.getKey(), entry.getValue());
			}
		}
		PlayerInventoryTransfer.Plan inventory = PlayerInventoryTransfer.prepare(player, List.of());
		if (inventory == null) {
			return false;
		}
		boolean creative = player.isCreative();
		CompoundTag upgrades = data.getCompound(NBT_KEY_PB_UPGRADES);
		for (String id : upgrades.getAllKeys()) {
			PbUpgradeType type = PbUpgradeType.byId(id);
			if (type == null || !isSupported(type, machineType)
					|| !BalanceConfig.canInstall(type, after)) {
				continue;
			}
			int requested = Math.max(0, Math.min(upgrades.getInt(id), limit.applyAsInt(type)));
			if (requested == 0) {
				continue;
			}
			ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(type);
			if (template.isEmpty()) {
				continue;
			}
			int installed = requested;
			if (!creative) {
				int reusable = Math.max(0, before.getOrDefault(type, 0));
				long available = inventory.countItem(template.getItem()) + reusable;
				installed = (int) Math.min(requested, available);
				int needed = Math.max(0, installed - reusable);
				if (!inventory.consume(template.getItem(), needed)) {
					throw new IllegalStateException("PB upgrade inventory plan lost its own reservation");
				}
			}
			if (installed > 0) {
				after.put(type, installed);
			}
		}
		if (!creative) {
			List<ItemStack> returned = new ArrayList<>();
			for (Map.Entry<PbUpgradeType, Integer> entry : before.entrySet()) {
				int remaining = entry.getValue() - after.getOrDefault(entry.getKey(), 0);
				if (remaining <= 0) {
					continue;
				}
				ItemStack template = PbUpgradeInventorySlot.getRepresentativeStack(entry.getKey());
				if (template.isEmpty()) {
					return false;
				}
				returned.add(template.copyWithCount(remaining));
			}
			if (!inventory.insertAll(returned)) {
				player.displayClientMessage(Component.translatable(
						"message.productivebeesgenesis.inventory_full"), true);
				return false;
			}
		}
		if (!before.equals(currentCounts) || !inventory.commit()) {
			return false;
		}
		replaceCounts.accept(Map.copyOf(after));
		return true;
	}

	/**
	 * 从配置卡数据读取AE2 per-tile状态
	 *
	 * @param data 配置卡NBT
	 * @return 包含物品和流体输出开关的数组，[0]=物品，[1]=流体；无数据返回null
	 */
	@Nullable
	public static boolean[] readAe2PerTileState(@NotNull CompoundTag data) {
		if (!data.contains(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT) && !data.contains(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT)) {
			return null;
		}
		boolean itemOutput = data.contains(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT)
				? data.getBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT) : true;
		boolean fluidOutput = data.contains(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT)
				? data.getBoolean(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT) : true;
		return new boolean[]{itemOutput, fluidOutput};
	}

	// ===== 机器类型判断 =====

	/** 机器类型枚举 — 用于确定支持的PB升级类型 */
	public enum MachineType {
		/** 离心机（支持产量+时间系列6种） */
		CENTRIFUGE,
		/** 蜂箱（支持所有非内置8种） */
		APIARY
	}

	/**
	 * 判断升级类型是否被机器支持
	 * <br/>
	 * STABILITY 仅离心机生效（对齐 PB 原版），蜂箱不接受。
	 */
	private static boolean isSupported(@NotNull PbUpgradeType type, @NotNull MachineType machineType) {
		if (type.isBuiltin()) return false;
		return switch (machineType) {
			case CENTRIFUGE -> switch (type) {
				case PRODUCTIVITY, PRODUCTIVITY_2, PRODUCTIVITY_3, PRODUCTIVITY_4,
						TIME, TIME_2, STABILITY, USELESS_BYPRODUCT, ESSENCE_CONVERSION, RAW_ORE_SMELTING -> true;
				default -> false;
			};
			case APIARY -> type != PbUpgradeType.STABILITY
					&& type != PbUpgradeType.RAW_ORE_SMELTING;
		};
	}
}
