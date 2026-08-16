package com.ayoshiko.productivebeesgenesis.recipe;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotSerializer;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import com.ayoshiko.productivebeesgenesis.util.SaturatingMath;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.interfaces.IHasTileEntity;
import mekanism.common.tier.FactoryTier;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * {@link ApiaryShapedRecipe} 的 BLOCK_ENTITY_DATA 转移/合并工具类（SRP 拆分）
 * <br/>
 * 从 ApiaryShapedRecipe 拆出，集中管理合成升级时的自定义 NBT 数据转移逻辑：
 * 蜜蜂槽取并集、PB 升级数量累加、BlockEntityType id 替换、降级路径深拷贝。
 * 所有方法为静态纯函数（不持有状态），便于单独测试。
 */
final class ApiaryCraftingDataTransfer {

	private ApiaryCraftingDataTransfer() {
	}

	/** 用于 WARN 日志的 feature 标识（DevLog 节流） */
	private static final String DEV_FEATURE = "crafting_upgrade";

	/**
	 * 将机器输入的 BLOCK_ENTITY_DATA 转移到合成结果
	 * <br/>
	 * 单输入深拷贝完整数据；多输入按合并策略合并（蜜蜂槽取并集、PB升级累加、其他取首个非空）。
	 * 正常路径与降级路径共用，保证数据转移逻辑一致。
	 * <p>
	 * 崩溃修复：BLOCK_ENTITY_DATA 组件使用 {@code CustomData.CODEC_WITH_ID} 校验顶层
	 * {@code "id"}（BlockEntityType 注册键），必须写入目标方块的 BlockEntityType id，
	 * 否则保存玩家背包/物品时抛 "Missing id for entity" 崩溃。
	 *
	 * @param inputs      同类型机器方块输入（已确认非空）
	 * @param dest        合成结果物品
	 * @param outputBlock 输出方块（用于多输入时查询蜜蜂槽容量）
	 * @param isApiary    输出是否为蜂箱（决定是否合并蜜蜂槽）
	 */
	static void transferAllBlockEntityData(List<ItemStack> inputs, ItemStack dest, Block outputBlock, boolean isApiary) {
		String targetTileId = resolveBlockEntityTypeId(outputBlock);
		if (inputs.size() == 1) {
			// 单输入场景：直接深拷贝 BLOCK_ENTITY_DATA 到输出
			transferBlockEntityDataDirect(inputs.get(0), dest, targetTileId);
		} else {
			// 多输入场景：按合并策略合并 NBT 后写入输出
			CompoundTag mergedNbt = mergeMachineInputs(inputs, outputBlock, isApiary, targetTileId);
			if (mergedNbt != null) {
				dest.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(mergedNbt));
			}
		}
	}

	/**
	 * 解析输出方块的 BlockEntityType 注册键（用于 BLOCK_ENTITY_DATA 的 "id" 字段）
	 * <br/>
	 * 本模组机器（蜂箱/离心机）实现 {@code IHasTileEntity}，通过 {@code getTileType()}
	 * 获取 {@link BlockEntityType}，再查 {@link BuiltInRegistries#BLOCK_ENTITY_TYPE} 的注册键。
	 * 解析失败返回 null（调用方保留输入 NBT 中已有的 id 作为兜底）。
	 *
	 * @param outputBlock 输出方块
	 * @return BlockEntityType 注册键字符串，解析失败返回 null
	 */
	@Nullable
	static String resolveBlockEntityTypeId(Block outputBlock) {
		if (outputBlock instanceof IHasTileEntity<?> hasTile) {
			BlockEntityType<?> type = hasTile.getTileType().get();
			ResourceLocation key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(type);
			if (key != null) {
				return key.toString();
			}
		}
		return null;
	}

	/**
	 * 收集合成矩阵中同类型的机器方块输入
	 * <br/>
	 * "同类型" 指与输出同种机器（蜂箱 / 离心机），不区分工厂等级。
	 * 例如：输出为 BASIC 工厂蜂箱时，所有基础蜂箱 / 任意工厂蜂箱输入均被收集。
	 *
	 * @param inv              合成矩阵快照
	 * @param expectApiary     输出是否为蜂箱
	 * @param expectCentrifuge 输出是否为离心机
	 * @return 同类型机器方块输入列表（保持矩阵扫描顺序）
	 */
	static List<ItemStack> collectMachineInputs(CraftingInput inv, boolean expectApiary, boolean expectCentrifuge) {
		List<ItemStack> inputs = new ArrayList<>(inv.size());
		for (int i = 0; i < inv.size(); i++) {
			ItemStack stack = inv.getItem(i);
			if (stack.isEmpty()) continue;
			if (!(stack.getItem() instanceof BlockItem bi)) continue;
			Block b = bi.getBlock();
			if (expectApiary && b instanceof MekApiaryBlock<?, ?>) {
				inputs.add(stack);
			} else if (expectCentrifuge && b instanceof MekCentrifugeBlock<?, ?>) {
				inputs.add(stack);
			}
		}
		return inputs;
	}

	/**
	 * 单输入场景：直接将输入物品的 BLOCK_ENTITY_DATA 深拷贝到输出物品
	 * <br/>
	 * 原理：CustomData.copyTag() 返回 CompoundTag 的深拷贝，
	 * CustomData.of(CompoundTag) 包装为新组件，set() 写入输出 ItemStack。
	 * 输入无 BLOCK_ENTITY_DATA（新合成的机器方块）时直接返回，不抛 NPE。
	 * <p>
	 * 崩溃修复：BLOCK_ENTITY_DATA 组件（{@code CustomData.CODEC_WITH_ID}）校验顶层
	 * {@code "id"} 字段，<b>不能 remove</b>。将 "id" 替换为目标方块的 BlockEntityType 注册键，
	 * 既满足编码校验，又保证放置时方块实体类型匹配、数据正确加载。
	 * 目标 id 解析失败时保留输入 NBT 中已有的 id（兜底，避免编码崩溃）。
	 *
	 * @param src          输入机器方块物品（已确认同类型）
	 * @param dest         合成输出物品
	 * @param targetTileId 目标方块的 BlockEntityType 注册键，null 时保留源 id
	 */
	static void transferBlockEntityDataDirect(ItemStack src, ItemStack dest, @Nullable String targetTileId) {
		CustomData srcData = src.get(DataComponents.BLOCK_ENTITY_DATA);
		if (srcData == null) {
			// 输入无 BLOCK_ENTITY_DATA（新合成机器方块），无需转移
			return;
		}
		CompoundTag nbt = srcData.copyTag();
		// 替换为目标方块的 BlockEntityType 注册键（不能 remove：CODEC_WITH_ID 校验顶层 id）
		if (targetTileId != null) {
			nbt.putString("id", targetTileId);
		}
		dest.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(nbt));
	}

	/**
	 * 多输入场景：按合并策略合并所有输入的 BLOCK_ENTITY_DATA
	 * <br/>
	 * 合并规则：
	 * <ul>
	 *   <li>蜜蜂槽（仅蜂箱）：取并集，按输入顺序填充到目标容量上限，超出 WARN</li>
	 *   <li>PB 升级数量：累加所有输入数量，按 {@link PbUpgradeType#getMaxCount()} 上限限制</li>
	 *   <li>其他字段：取首个非空（首输入的 NBT 作为 base，后续 merge 仅覆盖合并字段）</li>
	 *   <li>"id"：替换为目标方块的 BlockEntityType 注册键（不能 remove，见 {@link #transferBlockEntityDataDirect}）</li>
	 * </ul>
	 *
	 * @param inputs       同类型机器方块输入列表（已确认非空且 size >= 2）
	 * @param outputBlock  合成输出方块（用于查询目标容量）
	 * @param isApiary     输出是否为蜂箱（决定是否合并蜜蜂槽）
	 * @param targetTileId 目标方块的 BlockEntityType 注册键，null 时保留源 id
	 * @return 合并后的 CompoundTag，所有输入均无数据时返回 null
	 */
	static CompoundTag mergeMachineInputs(List<ItemStack> inputs, Block outputBlock, boolean isApiary,
			@Nullable String targetTileId) {
		// 收集所有输入的 NBT（深拷贝）
		List<CompoundTag> nbts = new ArrayList<>(inputs.size());
		for (ItemStack input : inputs) {
			CustomData data = input.get(DataComponents.BLOCK_ENTITY_DATA);
			if (data == null) continue;
			try {
				nbts.add(data.copyTag());
			} catch (Exception e) {
				DevLog.error("合成升级: 读取输入 BLOCK_ENTITY_DATA 失败,跳过该输入", e);
			}
		}
		if (nbts.isEmpty()) {
			return null;
		}

		// 第一个非空 NBT 作为 base，保留所有 "其他字段"（喂食槽/能量槽/流体罐/蜂笼 I/O/选中槽位等）
		CompoundTag merged = nbts.get(0);

		// 合并蜜蜂槽（仅蜂箱）
		if (isApiary) {
			int targetCapacity = resolveApiaryBeeSlotCapacity(outputBlock);
			mergeBeeSlots(merged, nbts, targetCapacity);
		}

		// 合并 PB 升级数量（蜂箱用 ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS,离心机用 MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS）
		String pbUpgradeKey = isApiary ? ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS
				: MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS;
		mergePbUpgradeCounts(merged, nbts, pbUpgradeKey);

		// 替换为目标方块的 BlockEntityType 注册键（不能 remove：CODEC_WITH_ID 校验顶层 id）
		if (targetTileId != null) {
			merged.putString("id", targetTileId);
		}

		return merged;
	}

	/**
	 * 合并蜜蜂槽数据 — 取并集，按输入顺序填充到目标容量上限
	 * <br/>
	 * 原理：遍历所有输入 NBT 的 {@link ApiarySlotSerializer#NBT_KEY_BEE_SLOTS} ListTag，
	 * 依次将每个蜜蜂槽 CompoundTag 追加到 merged 的 bee_slots 列表，
	 * 达到目标容量上限后停止追加，超出部分记录 WARN。
	 * <p>
	 * 向后兼容：输入 NBT 无 bee_slots 字段时跳过该输入。
	 *
	 * @param merged          合并目标 NBT（首输入 NBT 作为 base，已包含其 bee_slots）
	 * @param inputs          所有输入 NBT 列表
	 * @param targetCapacity  目标机器的蜜蜂槽容量上限
	 */
	static void mergeBeeSlots(CompoundTag merged, List<CompoundTag> inputs, int targetCapacity) {
		// 从 base 中取出已有蜜蜂槽（首输入的蜜蜂）
		ListTag mergedBeeSlots = merged.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)
				? merged.getList(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND)
				: new ListTag();
		// 容量校验：base 蜜蜂数已超目标容量时截断
		int dropped = 0;
		while (mergedBeeSlots.size() > targetCapacity) {
			mergedBeeSlots.remove(mergedBeeSlots.size() - 1);
			dropped++;
		}

		// 追加后续输入的蜜蜂槽（跳过首输入 NBT,已作为 base）
		for (int i = 1; i < inputs.size(); i++) {
			CompoundTag inputNbt = inputs.get(i);
			if (!inputNbt.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)) continue;
			ListTag inputBeeSlots = inputNbt.getList(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND);
			for (int j = 0; j < inputBeeSlots.size(); j++) {
				if (mergedBeeSlots.size() >= targetCapacity) {
					// 剩余蜜蜂全部丢弃
					dropped += (inputBeeSlots.size() - j);
					break;
				}
				mergedBeeSlots.add(inputBeeSlots.getCompound(j).copy());
			}
		}

		// 超出容量警告
		if (dropped > 0) {
			DevLog.warn(DEV_FEATURE,
					"合成升级合并蜜蜂槽超出目标容量,丢弃 {} 只蜜蜂 (目标容量={})",
					dropped, targetCapacity);
		}

		// 写回合并后的蜜蜂槽（空列表也写入,确保字段存在）
		merged.put(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, mergedBeeSlots);
	}

	/**
	 * 合并 PB 升级数量 — 累加所有输入的数量，按类型上限限制
	 * <br/>
	 * 原理：遍历所有输入 NBT 的 PB 升级数量 CompoundTag（key=类型id,value=数量），
	 * 按类型累加数量，超过 {@link PbUpgradeType#getMaxCount()} 时截断并 WARN。
	 * <p>
	 * 向后兼容：输入 NBT 无 PB 升级字段时跳过该输入。
	 *
	 * @param merged       合并目标 NBT
	 * @param inputs       所有输入 NBT 列表
	 * @param pbUpgradeKey PB 升级数量的 NBT key（蜂箱 / 离心机不同）
	 */
	static void mergePbUpgradeCounts(CompoundTag merged, List<CompoundTag> inputs, String pbUpgradeKey) {
		CompoundTag mergedCounts = merged.contains(pbUpgradeKey, Tag.TAG_COMPOUND)
				? merged.getCompound(pbUpgradeKey)
				: new CompoundTag();
		boolean overflowed = false;

		// 累加所有输入的数量（首输入已在 mergedCounts 中作为 base）
		for (int i = 1; i < inputs.size(); i++) {
			CompoundTag inputNbt = inputs.get(i);
			if (!inputNbt.contains(pbUpgradeKey, Tag.TAG_COMPOUND)) continue;
			CompoundTag inputCounts = inputNbt.getCompound(pbUpgradeKey);
			for (String typeId : inputCounts.getAllKeys()) {
				int inputValue = inputCounts.getInt(typeId);
				if (inputValue <= 0) continue;
				int current = Math.max(0, mergedCounts.getInt(typeId));
				mergedCounts.putInt(typeId, SaturatingMath.saturatingToInt(
						SaturatingMath.saturatingAdd(current, inputValue)));
			}
		}

		// 按类型上限截断（PB 升级类型限制由 PbUpgradeType.getMaxCount 决定）
		for (String typeId : mergedCounts.getAllKeys()) {
			PbUpgradeType type = PbUpgradeType.byId(typeId);
			if (type == null) {
				// 未知类型 ID（可能来自未来版本）,保留原值不截断
				continue;
			}
			int current = mergedCounts.getInt(typeId);
			int max = type.getMaxCount();
			if (current > max) {
				mergedCounts.putInt(typeId, max);
				overflowed = true;
				DevLog.warn(DEV_FEATURE,
						"合成升级合并 PB 升级 {} 超出上限,截断为 {} (输入合计={})",
						typeId, max, current);
			}
		}

		if (overflowed) {
			ProductiveBeesGenesis.LOGGER.debug(
					"[CraftingUpgrade] PB 升级合并发生超限截断,详见 DEV 日志");
		}

		// 写回合并后的 PB 升级数量（空 CompoundTag 也写入,确保字段存在）
		merged.put(pbUpgradeKey, mergedCounts);
	}

	/**
	 * 解析蜂箱输出方块的蜜蜂槽容量上限
	 * <br/>
	 * 通过 {@link Attribute#getTier(Block, Class)} 识别工厂等级：
	 * <ul>
	 *   <li>原版工厂（BASIC/ADVANCED/ELITE/ULTIMATE）：{@link FactoryApiaryConfig#forTier}</li>
	 *   <li>ME 工厂（ABSOLUTE/SUPREME/COSMIC/INFINITE）：通过 {@link MEContainerSlotHelper}（软依赖守卫）</li>
	 *   <li>EME 工厂：通过 {@link EMEContainerSlotHelper}（软依赖守卫）</li>
	 *   <li>基础蜂箱（非工厂）：返回 {@link ApiarySlotManager#DEFAULT_BEE_SLOT_COUNT}</li>
	 * </ul>
	 * <p>
	 * 性能：本方法仅在多输入合并路径调用,非热路径,无需缓存。
	 *
	 * @param outputBlock 输出方块
	 * @return 蜜蜂槽容量上限
	 */
	static int resolveApiaryBeeSlotCapacity(Block outputBlock) {
		// 1. 原版工厂等级
		FactoryTier tier = Attribute.getTier(outputBlock, FactoryTier.class);
		if (tier != null) {
			return FactoryApiaryConfig.forTier(tier).beeSlotCount;
		}
		// 2. ME 工厂等级（软依赖守卫,避免 NoClassDefFoundError）
		if (MekCompatHooks.isMekanismExtrasLoaded()) {
			int beeSlotCount = MEContainerSlotHelper.getBeeSlotCount(outputBlock);
			if (beeSlotCount > 0) {
				return beeSlotCount;
			}
		}
		// 3. EME 工厂等级（软依赖守卫）
		if (MekCompatHooks.isEvolvedMekanismExtrasLoaded()) {
			int beeSlotCount = EMEContainerSlotHelper.getBeeSlotCount(outputBlock);
			if (beeSlotCount > 0) {
				return beeSlotCount;
			}
		}
		// 4. 基础蜂箱（非工厂）
		return ApiarySlotManager.DEFAULT_BEE_SLOT_COUNT;
	}
}
