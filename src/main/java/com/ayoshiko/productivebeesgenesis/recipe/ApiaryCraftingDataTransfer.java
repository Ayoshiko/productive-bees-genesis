package com.ayoshiko.productivebeesgenesis.recipe;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotManager;
import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotSerializer;
import com.ayoshiko.productivebeesgenesis.apiary.FactoryApiaryConfig;
import com.ayoshiko.productivebeesgenesis.apiary.GeneTreatRestockState;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryBlock;
import com.ayoshiko.productivebeesgenesis.compat.emextras.EMEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.MEContainerSlotHelper;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugePbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.mek.MekCompatHooks;
import mekanism.common.block.attribute.Attribute;
import mekanism.common.block.interfaces.IHasTileEntity;
import mekanism.common.tier.FactoryTier;
import mekanism.common.registries.MekanismDataComponents;
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
import java.util.Set;

/**
 * {@link ApiaryShapedRecipe} 的 BLOCK_ENTITY_DATA 转移/合并工具类（SRP 拆分）
 * <br/>
 * 从 ApiaryShapedRecipe 拆出，集中管理合成升级时的自定义 NBT 数据转移逻辑：
 * 蜜蜂槽取并集、PB 升级数量累加、BlockEntityType id 替换及单输入完整快照。
 * 所有方法为静态纯函数（不持有状态），便于单独测试。
 */
final class ApiaryCraftingDataTransfer {

	private ApiaryCraftingDataTransfer() {
	}

	/**
	 * 将机器输入的 BLOCK_ENTITY_DATA 转移到合成结果
	 * <br/>
	 * 单输入深拷贝完整数据；多输入按合并策略合并（蜜蜂槽取并集、PB升级累加、其他取首个非空）。
	 * 单输入保留全部自定义字段，多输入只合并可证明无损的资产。
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
		// MEK 合成只合并库存等升级数据；侧面组件采用矩阵中首份设置。
		for (var input : inputs) {
			var config = input.get(MekanismDataComponents.SIDE_CONFIG);
			if (config != null) {
				dest.set(MekanismDataComponents.SIDE_CONFIG, config);
				break;
			}
		}
		for (var input : inputs) {
			var ejector = input.get(MekanismDataComponents.EJECTOR);
			if (ejector != null) {
				dest.set(MekanismDataComponents.EJECTOR, ejector);
				break;
			}
		}
		if (inputs.size() == 1) {
			CompoundTag source = readBlockEntityData(inputs.getFirst());
			if (isApiary && source != null && source.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS)) {
				ApiarySlotSerializer.validatedBeeSlots(source.get(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS),
						resolveApiaryBeeSlotCapacity(outputBlock));
			}
			// 单输入场景：直接深拷贝 BLOCK_ENTITY_DATA 到输出
			transferBlockEntityDataDirect(inputs.get(0), dest, targetTileId);
		} else {
			// 多输入场景：按合并策略合并 NBT 后写入输出
			CompoundTag mergedNbt = mergeMachineInputs(inputs, outputBlock, isApiary, targetTileId, dest);
			if (mergedNbt != null) {
				dest.set(DataComponents.BLOCK_ENTITY_DATA, CustomData.of(mergedNbt));
			}
		}
	}

	/** 读取独立保存快照；新机器没有自定义数据。 */
	@Nullable
	private static CompoundTag readBlockEntityData(@Nullable ItemStack stack) {
		if (stack == null || stack.isEmpty()) return null;
		CustomData data = stack.get(DataComponents.BLOCK_ENTITY_DATA);
		return data == null ? null : data.copyTag();
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
	 *   <li>蜜蜂槽（仅蜂箱）：完整迁移并重新编号，容量不足时拒绝合成</li>
	 *   <li>PB 升级数量：精确累加所有输入的持有数量</li>
	 *   <li>其他字段：取首个非空（首输入的 NBT 作为 base，后续 merge 仅覆盖合并字段）</li>
	 *   <li>"id"：替换为目标方块的 BlockEntityType 注册键（不能 remove，见 {@link #transferBlockEntityDataDirect}）</li>
	 * </ul>
	 *
	 * @param inputs       同类型机器方块输入列表（已确认非空且 size >= 2）
	 * @param outputBlock  合成输出方块（用于查询目标容量）
	 * @param isApiary     输出是否为蜂箱（决定是否合并蜜蜂槽）
	 * @param targetTileId 目标方块的 BlockEntityType 注册键，null 时保留源 id
	 * @param dest         合成结果物品；其上可能已有 MEK 合并好的 BLOCK_ENTITY_DATA
	 * @return 合并后的 CompoundTag，所有输入均无数据时返回 null
	 */
	static CompoundTag mergeMachineInputs(List<ItemStack> inputs, Block outputBlock, boolean isApiary,
			@Nullable String targetTileId, @Nullable ItemStack dest) {
		// 收集所有输入的 NBT（深拷贝）
		List<CompoundTag> nbts = new ArrayList<>(inputs.size());
		List<CompoundTag> restockStates = new ArrayList<>(inputs.size());
		for (ItemStack input : inputs) {
			CustomData data = input.get(DataComponents.BLOCK_ENTITY_DATA);
			if (data == null) {
				if (isApiary) restockStates.add(new CompoundTag());
				continue;
			}
			try {
				CompoundTag nbt = data.copyTag();
				validateMergeableAssets(nbt);
				if (isApiary && nbt.contains(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS)) {
					Block sourceBlock = ((BlockItem) input.getItem()).getBlock();
					ApiarySlotSerializer.validatedBeeSlots(nbt.get(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS),
							resolveApiaryBeeSlotCapacity(sourceBlock));
				}
				nbts.add(nbt);
				if (isApiary) {
					if (nbt.contains(GeneTreatRestockState.NBT_KEY)
							&& !nbt.contains(GeneTreatRestockState.NBT_KEY, Tag.TAG_COMPOUND)) {
						throw new IllegalArgumentException("Invalid gene-treat restock root");
					}
					restockStates.add(nbt.getCompound(GeneTreatRestockState.NBT_KEY));
				}
			} catch (Exception e) {
				throw new IllegalArgumentException("Cannot read machine input data", e);
			}
		}

		// 目标模板有自定义默认字段时沿用目标快照，否则沿用首个输入。
		// 物品和流体附件已经独立按目标布局合并，此处只处理自定义保存状态。
		CompoundTag merged = readBlockEntityData(dest);
		if (merged == null) {
			if (nbts.isEmpty()) {
				return null;
			}
			merged = nbts.get(0);
		} else if (nbts.isEmpty()) {
			// dest 有数据但输入都读不出来：只需修正 id 即可返回
			if (targetTileId != null) {
				merged.putString("id", targetTileId);
			}
			return merged;
		}

		// 合并蜜蜂槽（仅蜂箱）—— 从全部输入重建，结果与 base 来源无关
		if (isApiary) {
			int targetCapacity = resolveApiaryBeeSlotCapacity(outputBlock);
			mergeBeeSlots(merged, nbts, targetCapacity);
			merged.put(GeneTreatRestockState.NBT_KEY, GeneTreatRestockState.mergeSaved(restockStates));
		}

		// 合并 PB 升级数量（蜂箱用 ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS,离心机用 MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS）
		// 同样从全部输入重新求和：若仍沿用「首输入已在 base 中」的假设，一旦 base 换成
		// MEK 的合并结果（其中不含我们的 PB 字段），首输入的升级数就会被漏算。
		String pbUpgradeKey = isApiary ? ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS
				: MekCentrifugePbUpgradeHandler.NBT_KEY_COUNTS;
		mergePbUpgradeCounts(merged, nbts, pbUpgradeKey);
        for (var nbt : nbts) {
            if (nbt.contains(com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence.KEY)) {
                merged.put(com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence.KEY,
                        nbt.get(com.ayoshiko.productivebeesgenesis.resource.ResourceSidePersistence.KEY).copy());
                break;
            }
        }

		// 替换为目标方块的 BlockEntityType 注册键（不能 remove：CODEC_WITH_ID 校验顶层 id）
		if (targetTileId != null) {
			merged.putString("id", targetTileId);
		}

		return merged;
	}

	/**
	 * 合并蜜蜂槽数据 — 从<b>全部输入</b>重建，按输入顺序完整迁移并分配唯一索引
	 * <br/>
	 * 原理：清空 merged 中已有的 {@link ApiarySlotSerializer#NBT_KEY_BEE_SLOTS}，按输入顺序
	 * 依次追加每个输入的蜜蜂槽 CompoundTag，超出目标容量时整体拒绝。
	 * <p>
	 * <b>为什么从零重建而不是「base 续接」</b>：base 可能是首输入的原始 NBT，也可能是 MEK 的
	 * 合并结果（见 {@link #mergeMachineInputs}）。若沿用「首输入已在 base 中」的假设，
	 * base 一换就会重复计入或漏算首输入的蜜蜂。从零重建让结果与 base 来源完全无关，
	 * 且输出顺序与原先逐字一致（首输入的蜜蜂仍在最前）。
	 * <p>
	 * 向后兼容：输入 NBT 无 bee_slots 字段时跳过该输入；空列表也写入，确保字段存在。
	 *
	 * @param merged          合并目标 NBT
	 * @param inputs          所有输入 NBT 列表
	 * @param targetCapacity  目标机器的蜜蜂槽容量上限
	 */
	static void mergeBeeSlots(CompoundTag merged, List<CompoundTag> inputs, int targetCapacity) {
		ListTag mergedBeeSlots = new ListTag();
		for (CompoundTag inputNbt : inputs) {
			Tag raw = inputNbt.get(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS);
			if (raw == null) continue;
			ListTag inputBeeSlots = ApiarySlotSerializer.validatedBeeSlots(raw, Integer.MAX_VALUE);
			if ((long) mergedBeeSlots.size() + inputBeeSlots.size() > targetCapacity) {
				throw new IllegalArgumentException("Target apiary cannot hold every input bee");
			}
			for (Tag entry : inputBeeSlots) {
				CompoundTag transferred = ((CompoundTag) entry).copy();
				transferred.putInt("slot_index", mergedBeeSlots.size());
				mergedBeeSlots.add(transferred);
			}
		}
		merged.put(ApiarySlotSerializer.NBT_KEY_BEE_SLOTS, mergedBeeSlots);
		merged.putInt("productivebeesgenesis_selected_bee", -1);
	}

	/**
	 * 合并 PB 升级数量 — 从<b>全部输入</b>精确求和
	 * <br/>
	 * 原理：清空 merged 中已有的升级计数，遍历所有输入 NBT 的 PB 升级数量 CompoundTag
	 * （key=类型id,value=数量），按类型累加，错误类型或整数溢出时整体拒绝。
	 * <p>
	 * 与 {@link #mergeBeeSlots} 同理：从零求和不依赖 base 来源，避免 base 换成 MEK 合并结果后
	 * 首输入升级数被漏算。
	 *
	 * @param merged       合并目标 NBT
	 * @param inputs       所有输入 NBT 列表
	 * @param pbUpgradeKey PB 升级数量的 NBT key（蜂箱 / 离心机不同）
	 */
	static void mergePbUpgradeCounts(CompoundTag merged, List<CompoundTag> inputs, String pbUpgradeKey) {
		CompoundTag mergedCounts = new CompoundTag();
		for (CompoundTag inputNbt : inputs) {
			if (!inputNbt.contains(pbUpgradeKey)) continue;
			if (!inputNbt.contains(pbUpgradeKey, Tag.TAG_COMPOUND)) {
				throw new IllegalArgumentException("Invalid PB upgrade counts");
			}
			CompoundTag inputCounts = inputNbt.getCompound(pbUpgradeKey);
			for (String typeId : inputCounts.getAllKeys()) {
				if (!inputCounts.contains(typeId, Tag.TAG_INT) || inputCounts.getInt(typeId) < 0) {
					throw new IllegalArgumentException("Invalid PB upgrade count");
				}
				int count = Math.addExact(mergedCounts.getInt(typeId), inputCounts.getInt(typeId));
				if (count > 0) mergedCounts.putInt(typeId, count);
			}
		}
		// 安装上限影响新安装；已经持有的升级数量必须完整保留。
		merged.put(pbUpgradeKey, mergedCounts);
	}

	/** 多台机器不能共享同一笔未结算资产；未完成的工作留在原机器。 */
	private static void validateMergeableAssets(CompoundTag nbt) {
		requireEmptyList(nbt, "productivebeesgenesis_pb_committed_pending");
		if (nbt.contains("productivebeesgenesis_pb_progress")) {
			if (!nbt.contains("productivebeesgenesis_pb_progress", Tag.TAG_INT_ARRAY)) {
				throw new IllegalArgumentException("Invalid centrifuge paid progress");
			}
			for (int progress : nbt.getIntArray("productivebeesgenesis_pb_progress")) {
				if (progress != 0) throw new IllegalArgumentException("Unsettled centrifuge progress");
			}
		}
		if (nbt.contains("productivebeesgenesis_pb_paid_work")) {
			if (!(nbt.get("productivebeesgenesis_pb_paid_work") instanceof CompoundTag work)
					|| !Set.of("version", "entries", "paused").containsAll(work.getAllKeys())
					|| !work.contains("version", Tag.TAG_INT)
					|| work.getInt("version") != 1 && work.getInt("version") != 2
					|| work.getInt("version") == 1 && work.contains("paused")
					|| !work.contains("entries", Tag.TAG_LIST)
					|| work.getInt("version") == 2 && !work.contains("paused", Tag.TAG_LIST)) {
				throw new IllegalArgumentException("Invalid centrifuge paid work");
			}
			requireEmptyList(work, "entries");
			requireEmptyList(work, "paused");
		}
		if (nbt.contains("productivebeesgenesis_output_buffer")) {
			Tag raw = nbt.get("productivebeesgenesis_output_buffer");
			if (!(raw instanceof CompoundTag buffer)
					|| !Set.of("version", "production", "stacks").containsAll(buffer.getAllKeys())) {
				throw new IllegalArgumentException("Unresolved apiary output buffer");
			}
			requireEmptyList(buffer, "stacks");
			if (buffer.contains("production")) {
				if (!(buffer.get("production") instanceof CompoundTag production)
						|| !Set.of("version", "live_slots", "pending", "retained", "items", "fluid_amount")
								.containsAll(production.getAllKeys())) {
					throw new IllegalArgumentException("Unresolved apiary production");
				}
				requireEmptyList(production, "pending");
				requireEmptyList(production, "retained");
				requireEmptyList(production, "items");
				if (production.contains("fluid_amount") && (!production.contains("fluid_amount", Tag.TAG_LONG)
						|| production.getLong("fluid_amount") != 0)) {
					throw new IllegalArgumentException("Unsettled apiary fluid");
				}
			}
		}
		for (String key : Set.of("productivebeesgenesis_apiary_fluid",
				"productivebeesgenesis_ae_pending_items", "productivebeesgenesis_ae_output_ledger")) {
			Tag value = nbt.get(key);
			if (value != null && (!(value instanceof CompoundTag compound) || !compound.isEmpty())
					&& (!(value instanceof ListTag list) || !list.isEmpty())) {
				throw new IllegalArgumentException("Unsettled machine asset: " + key);
			}
		}
		if (nbt.contains("neoforge:attachments")) {
			if (!(nbt.get("neoforge:attachments") instanceof CompoundTag attachments)) {
				throw new IllegalArgumentException("Invalid machine attachments");
			}
			Tag raw = attachments.get("productivebeesgenesis:resource_host");
			if (raw != null && (!(raw instanceof CompoundTag host)
					|| !host.contains("version", Tag.TAG_INT) || host.getInt("version") != 1
					|| !Set.of("version", "native_output").containsAll(host.getAllKeys()))) {
				throw new IllegalArgumentException("Resource identity cannot be merged");
			}
		}
	}

	private static void requireEmptyList(CompoundTag parent, String key) {
		Tag raw = parent.get(key);
		if (raw != null && (!(raw instanceof ListTag list) || !list.isEmpty())) {
			throw new IllegalArgumentException("Unsettled machine asset: " + key);
		}
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
