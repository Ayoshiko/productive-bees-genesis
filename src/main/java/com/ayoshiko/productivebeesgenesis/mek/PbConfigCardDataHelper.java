package com.ayoshiko.productivebeesgenesis.mek;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2NbtKeys;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.Level;

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
 * @since 1.9.0
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
	 * 从配置卡数据读取PB升级并应用到目标机器
	 * <br/>
	 * 生存模式下需要从玩家背包消耗对应PB升级物品，创造模式直接安装。
	 * 物品不足的类型跳过安装。
	 * <p>
	 * 物品守恒修复：clearFunc 返回被清空的 PB 升物品栈列表，本方法负责注入玩家物品栏
	 * 或在物品栏满时掉落地面，避免物品凭空消失。
	 *
	 * @param data         配置卡NBT
	 * @param player       执行粘贴的玩家
	 * @param installFunc  安装函数（type -> boolean，返回是否安装成功）
	 * @param clearFunc    清空函数（移除所有已安装PB升级，粘贴前调用，返回被清空的物品栈列表）
	 * @param machineType  机器类型
	 */
	public static void readAndApplyPbUpgrades(@NotNull CompoundTag data,
			@Nullable Player player,
			@NotNull Function<PbUpgradeType, Boolean> installFunc,
			@NotNull Supplier<List<ItemStack>> clearFunc,
			@NotNull MachineType machineType) {
		if (!data.contains(NBT_KEY_PB_UPGRADES, CompoundTag.TAG_COMPOUND)) {
			return;
		}
		// 先清空目标机器现有PB升级，并获取被清空的物品栈列表（物品守恒修复）
		List<ItemStack> cleared = clearFunc.get();
		// 将被清空的物品返还给玩家（创造模式玩家不返还，避免重复）
		if (player != null && !player.isCreative() && !cleared.isEmpty()) {
			returnClearedItemsToPlayer(player, cleared);
		}
		CompoundTag upgradesTag = data.getCompound(NBT_KEY_PB_UPGRADES);
		boolean creative = player != null && player.isCreative();
		for (String typeId : upgradesTag.getAllKeys()) {
			PbUpgradeType type = PbUpgradeType.byId(typeId);
			if (type == null || type.isBuiltin() || !isSupported(type, machineType)) {
				continue;
			}
			int targetCount = upgradesTag.getInt(typeId);
			if (targetCount <= 0) continue;
			if (creative) {
				// 创造模式直接安装
				for (int i = 0; i < targetCount; i++) {
					if (!installFunc.apply(type)) break;
				}
			} else {
				// 生存模式从玩家背包消耗物品
				if (player == null) continue;
				int installed = consumeAndInstall(player, type, targetCount, installFunc);
				if (installed < targetCount && installed == 0) {
					// 完全无法安装，跳过（不报错，静默失败）
					continue;
				}
			}
		}
	}

	/**
	 * 将被清空的 PB 升级物品栈列表注入玩家物品栏，满则掉落地面
	 * <br/>
	 * 修复物品守恒违反：原 clearAllPbUpgrades 丢弃 removePbUpgrade 返回的物品栈列表，
	 * 导致玩家粘贴配置卡时已安装的升级凭空消失。
	 * <p>
	 * Inventory.add 语义：返回 true 表示完全接收（输入栈被消费），
	 * 返回 false 时输入栈可能被部分消费（残留未接收部分），需将剩余掉落。
	 *
	 * @param player  执行粘贴操作的玩家
	 * @param items   被清空的 PB 升级物品栈列表
	 */
	private static void returnClearedItemsToPlayer(@NotNull Player player, @NotNull List<ItemStack> items) {
		for (ItemStack stack : items) {
			if (stack.isEmpty()) continue;
			// 复制后传给 add（add 可能修改输入栈，残留部分在副本中）
			ItemStack toAdd = stack.copy();
			boolean accepted = player.getInventory().add(toAdd);
			if (!accepted && !toAdd.isEmpty()) {
				// add 返回 false 时，toAdd 包含未接收的剩余物品
				dropItemStackAtPlayer(player, toAdd);
			}
		}
	}

	/**
	 * 在玩家位置掉落物品栈
	 */
	private static void dropItemStackAtPlayer(@NotNull Player player, @NotNull ItemStack stack) {
		Level level = player.level();
		if (level.isClientSide()) {
			// 客户端不应执行掉落（应由服务端处理）
			return;
		}
		BlockPos pos = player.blockPosition();
		// 使用 ServerLevel.addFreshEntity 掉落物品（参照 Block.popResource 模式）
		ItemEntity itemEntity = new ItemEntity(level, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, stack);
		// 设置轻微随机速度避免物品堆叠在同一位置
		itemEntity.setDeltaMovement(
				(level.random.nextDouble() - 0.5) * 0.2,
				0.2,
				(level.random.nextDouble() - 0.5) * 0.2);
		if (level instanceof ServerLevel serverLevel) {
			serverLevel.addFreshEntity(itemEntity);
		} else {
			// 兜底：直接调用 level.addFreshEntity（其他 Level 子类兜底）
			level.addFreshEntity(itemEntity);
		}
	}

	/**
	 * 从玩家背包消耗PB升级物品并安装到机器
	 *
	 * @param player       玩家
	 * @param type         升级类型
	 * @param targetCount  目标安装数量
	 * @param installFunc  安装函数
	 * @return 实际安装数量
	 */
	private static int consumeAndInstall(@NotNull Player player,
			@NotNull PbUpgradeType type,
			int targetCount,
			@NotNull java.util.function.Function<PbUpgradeType, Boolean> installFunc) {
		ItemStack upgradeStack = PbUpgradeInventorySlot.getRepresentativeStack(type);
		if (upgradeStack.isEmpty()) return 0;
		Item upgradeItem = upgradeStack.getItem();
		// 统计玩家背包中的升级物品数量
		int available = countItemInInventory(player, upgradeItem);
		if (available <= 0) return 0;
		int toInstall = Math.min(targetCount, available);
		int installed = 0;
		for (int i = 0; i < toInstall; i++) {
			if (installFunc.apply(type)) {
				installed++;
			} else {
				break;
			}
		}
		// 消耗对应数量的物品
		if (installed > 0) {
			consumeItemFromInventory(player, upgradeItem, installed);
		}
		return installed;
	}

	/**
	 * 统计玩家主物品栏中指定物品的数量
	 */
	private static int countItemInInventory(@NotNull Player player, @NotNull Item item) {
		int count = 0;
		for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				count += stack.getCount();
			}
		}
		return count;
	}

	/**
	 * 从玩家主物品栏消耗指定数量的物品
	 */
	private static void consumeItemFromInventory(@NotNull Player player, @NotNull Item item, int amount) {
		int remaining = amount;
		for (int i = 0; i < player.getInventory().getContainerSize() && remaining > 0; i++) {
			ItemStack stack = player.getInventory().getItem(i);
			if (!stack.isEmpty() && stack.getItem() == item) {
				int toRemove = Math.min(remaining, stack.getCount());
				stack.shrink(toRemove);
				remaining -= toRemove;
				if (stack.isEmpty()) {
					player.getInventory().setItem(i, ItemStack.EMPTY);
				}
			}
		}
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
						TIME, TIME_2, STABILITY -> true;
				default -> false;
			};
			case APIARY -> type != PbUpgradeType.STABILITY;
		};
	}
}
