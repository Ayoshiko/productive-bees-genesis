package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.FlowerPreference;
import cy.jdkdigital.productivebees.init.ModTags;
import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


/**
	 * 喂食器槽位管理器
	 * <br/>
	 * 管理喂食器窗口内的喂食槽矩阵，用于放置花朵物品供蜜蜂采集。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅管理喂食槽数据结构，不涉及蜜蜂生产逻辑或 tick 处理</li>
	 *   <li>开闭原则：花朵有效性检测通过 {@link #hasValidFlower} 按 PB 花朵配方系统精确匹配（Task E-2）</li>
	 * </ul>
	 * <p>
	 * 阶段五改造：喂食槽数量改为构造参数传入，支持工厂版动态数量（初始版9=3×3，
	 * 工厂版按 ceil(max(蜂蜂数,9)/3)*3 计算，最高 60 槽 3×20），保留 DEFAULT_FEEDER_SLOT_COUNT 作为初始版默认值。
	 */
public class FeederSlotManager {

	private static final ResourceLocation RANCHER_BEE_TYPE =
			ResourceLocation.fromNamespaceAndPath("productivebees", "rancher_bee");

	/** 初始版喂食槽数量（3×3 矩形） */
	public static final int DEFAULT_FEEDER_SLOT_COUNT = 9;

	/** 初始版喂食槽列数 */
	public static final int DEFAULT_FEEDER_COLS = 3;

	/** 初始版喂食槽行数 */
	public static final int DEFAULT_FEEDER_ROWS = 3;

	/** 喂食槽尺寸（像素） */
	public static final int SLOT_SIZE = 18;

	/** 喂食槽数量 */
	private final int feederSlotCount;

	/** 喂食槽列数 */
	private final int feederCols;

	/** 喂食槽行数 */
	private final int feederRows;

	/** 喂食槽列表（FeederInventorySlot 类型，支持 VirtualInventoryContainerSlot 创建） */
	private final List<FeederInventorySlot> feederSlots;

	/** 花朵有效性缓存（按蜜蜂类型键，喂食槽变化时主动失效） */
	private final FlowerValidityCache flowerValidityCache = new FlowerValidityCache();

	/** 上次同步的转化配方版本号 — 配方重载后失效花朵缓存（转化原料作为花朵来源） */
	private int lastConversionQueriesVersion = -1;

	/**
	 * 默认构造（初始版参数：3×3=9 个喂食槽）
	 * <br/>
	 * 向后兼容：保留与原版相同的参数。
	 */
	public FeederSlotManager() {
		this(DEFAULT_FEEDER_SLOT_COUNT, DEFAULT_FEEDER_COLS, DEFAULT_FEEDER_ROWS);
	}

	/**
	 * 工厂版构造（动态参数）
	 *
	 * @param feederSlotCount 喂食槽位数量
	 * @param feederCols      喂食槽列数
	 * @param feederRows      喂食槽行数
	 */
	public FeederSlotManager(int feederSlotCount, int feederCols, int feederRows) {
		this.feederSlotCount = feederSlotCount;
		this.feederCols = feederCols;
		this.feederRows = feederRows;
		this.feederSlots = new ArrayList<>(feederSlotCount);
	}

	/**
	 * 构建喂食槽列表
	 * <br/>
	 * 创建指定数量的 FeederInventorySlot，坐标为 (0, 0)，实际渲染位置由 GuiVirtualSlot 管理。
	 * 喂食槽配置为仅手动交互（玩家可放入/取出，拒绝外部自动化）。
	 *
	 * @param listener 内容变更监听器（标记方块实体需要保存）
	 * @return 喂食槽列表（IInventorySlot 只读视图，Collections.unmodifiableList 返回原 List 的只读视图而非副本）
	 */
	public List<IInventorySlot> buildFeederSlots(IContentsListener listener) {
		feederSlots.clear();
		IContentsListener combined = () -> {
			// 喂食槽内容变化时立即失效花朵缓存，保证下次 hasValidFlower 重新计算
			invalidateFlowerCache();
			if (listener != null) listener.onContentsChanged();
		};
		for (int i = 0; i < feederSlotCount; i++) {
			feederSlots.add(FeederInventorySlot.create(combined));
		}
		return Collections.unmodifiableList(feederSlots);
	}

	/** 获取 FeederInventorySlot 类型列表（供 Container 创建虚拟槽位包装） */
	List<FeederInventorySlot> getFeederInventorySlots() {
		return feederSlots;
	}

	/**
	 * 检查指定蜜蜂类型是否有有效花朵（Task E-2）
	 * <br/>
	 * 按蜜蜂类型精确匹配花朵：
	 * <ul>
	 *   <li>flowerType 为 "blocks" 且有花朵定义：遍历喂食槽精确匹配</li>
	 *   <li>其他情况（entity_types / 无定义）：回退到任意花朵检查（向后兼容）</li>
	 * </ul>
	 * 精确匹配逻辑参考 PB ConfigurableBee.isFlowerBlock / isFlowerItem：
	 * <ul>
	 *   <li>flowerTag：检查物品标签，BlockItem 同时检查方块标签（兼容仅创建方块标签的模组）</li>
	 *   <li>flowerItem：检查 ItemStack 是否为指定物品</li>
	 *   <li>flowerFluid：检查 ItemStack 是否为指定流体的桶</li>
	 * </ul>
	 * <p>
	 * 使用 {@link BeeNbtHelper#resolveBeeTypeKey} 解析蜜蜂类型键（如 productivebees:iron），
	 * 而非 {@code EntityType.getKey()}（对 ConfigurableBee 只返回 productivebees:configurable_bee），
	 * 确保能查询到 BeeReloadListener 中的具体花朵偏好数据。
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @return true 如果喂食槽中有匹配的花朵物品
	 */
	public boolean hasValidFlower(CompoundTag beeData) {
		ResourceLocation beeTypeKey = BeeNbtHelper.resolveBeeTypeKey(beeData);
		if (beeTypeKey == null) {
			// 无法解析蜜蜂类型键，回退到任意花朵检查
			return hasAnyFlower();
		}
		return hasValidFlower(beeTypeKey);
	}

	/**
	 * 检查指定蜜蜂类型是否有有效花朵（按 beeTypeKey 缓存版本）
	 * <br/>
	 * 使用 {@link #flowerValidityCache} 缓存蜜蜂类型→花朵匹配结果，
	 * 喂食槽变化时通过 {@link #invalidateFlowerCache()} 主动清空。
	 * 256× 加速下被高频调用，使用普通 HashMap 避免 LinkedHashMap access-order
	 * 的 afterNodeAccess 链表重排开销。
	 *
	 * @param beeTypeKey 蜜蜂类型键（非 null）
	 * @return true 如果喂食槽中有匹配的花朵物品
	 */
	public boolean hasValidFlower(ResourceLocation beeTypeKey) {
		// 配方重载后转化配方可能变化，失效花朵缓存保证转化花朵判定不过期
		if (lastConversionQueriesVersion != BeeConversionQueries.getVersion()) {
			flowerValidityCache.invalidate();
			lastConversionQueriesVersion = BeeConversionQueries.getVersion();
		}
		Boolean cached = flowerValidityCache.get(beeTypeKey);
		if (cached != null) {
			return cached;
		}

		boolean result = computeHasValidFlower(beeTypeKey);
		flowerValidityCache.put(beeTypeKey, result);
		return result;
	}




	/**
	 * 失效花朵有效性缓存 — 喂食槽内容变化时由外部调用
	 * <br/>
	 * 由 FeederInventorySlot 的 IContentsListener 在 setStack 变更时调用，
	 * 确保缓存与喂食槽实际内容保持一致。
	 */
	public void invalidateFlowerCache() {
		flowerValidityCache.invalidate();
	}


	/** 当前花朵缓存版本号（供外部缓存层判断失效） */
	public int getFlowerCacheVersion() {
		return flowerValidityCache.version();
	}

	private boolean computeHasValidFlower(ResourceLocation beeTypeKey) {
		// PB isFlowerItem/isFlowerBlock 语义：转化原料（物品转化 / 方块转化 BlockItem）
		// 对任意花朵类型（blocks/entity_types/Rancher）都是有效花朵，与 flowerType 无关——
		// 必须在 Rancher/实体早退分支之前检查，否则 entity_types 类蜜蜂放入转化原料
		// 会因花朵判定失败导致 pending 清零、转化永不执行。
		if (BeeConversionQueries.hasAnyConversionRecipe(beeTypeKey)
				&& hasConversionFlowerInFeeder(beeTypeKey)) {
			return true;
		}
		FlowerPreference pref = BeeInfoHelper.getFlowerPreference(beeTypeKey);

		// Rancher 是固定蜜蜂，不经过 BeeReloadListener 的 configurable flower 数据。
		if (RANCHER_BEE_TYPE.equals(beeTypeKey)) {
			return AmberEntityFlowerHelper.hasContainedEntityAmberMatching(feederSlots, ModTags.RANCHABLES, false);
		}
		// Butcher 等 configurable entity_types 蜜蜂使用数据包声明的实体 ID 或实体标签。
		if (FlowerPreference.TYPE_ENTITY_TYPES.equals(pref.flowerType())) {
			return AmberEntityFlowerHelper.hasContainedEntityAmberMatching(feederSlots, pref);
		}
		// 非 blocks 类型或无花朵定义：回退到任意花朵检查（向后兼容）
		if (!FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType()) || !pref.hasFlowerDefinition()) {
			return hasAnyFlower();
		}

		// 精确匹配：遍历喂食槽检查是否有匹配的花朵物品（转化原料已在函数开头统一检查）
		for (int i = 0; i < feederSlots.size(); i++) {
			ItemStack stack = feederSlots.get(i).getStack();
			if (stack.isEmpty()) continue;
			if (matchesFlowerPreference(stack, pref)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 遍历饲养板检查是否存在该蜜蜂的转化原料（物品转化 / 方块转化 BlockItem）
	 * <br/>
	 * 结果由 {@link #flowerValidityCache} 按蜜蜂类型缓存，仅在喂食槽内容变化时重算。
	 *
	 * @param beeTypeKey 蜜蜂类型键
	 * @return true 如果饲养板中存在转化原料
	 */
	private boolean hasConversionFlowerInFeeder(ResourceLocation beeTypeKey) {
		for (int i = 0; i < feederSlots.size(); i++) {
			ItemStack stack = feederSlots.get(i).getStack();
			if (stack.isEmpty()) {
				continue;
			}
			if (BeeConversionQueries.hasFeederConversionFlower(beeTypeKey, stack)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 检查物品栈是否匹配花朵偏好
	 * <br/>
	 * 参考 PB ConfigurableBee.isFlowerBlock / isFlowerItem 和 JDTE matchesConfiguredBlockFlower 的匹配逻辑：
	 * <ol>
	 *   <li>flowerTag：先检查物品标签（TagKey&lt;Item&gt;），若不匹配且物品为 BlockItem，再检查方块标签（TagKey&lt;Block&gt;）</li>
	 *   <li>flowerItem：ItemStack.is(Item)</li>
	 *   <li>flowerBlock：BlockItem 对应方块的注册表 ID 精确匹配</li>
	 *   <li>flowerFluid：BucketItem.content 匹配流体或流体标签</li>
	 * </ol>
	 * 任一匹配即返回 true。不检查 inverseFlower（与 PB isFlowerItem 行为一致）。
	 * <p>
	 * flowerTag 双重检查原理：PB 的 flowerTag 字段在 isFlowerBlock 中检查方块标签（TagKey&lt;Block&gt;），
	 * 在 isFlowerItem 中检查物品标签（TagKey&lt;Item&gt;）。部分模组（如 JDTE）仅创建方块标签而无对应物品标签，
	 * 例如 jdte:life_fluid_bee_flowers 仅包含 jdte:advanced_life_extractor 和 jdte:extended_life_extractor 两个方块。
	 * 当玩家将此类方块作为物品放入采蜜槽时，仅检查物品标签会漏匹配，必须同时检查方块标签。
	 *
	 * @param stack 待检查的物品栈
	 * @param pref  花朵偏好
	 * @return true 如果物品匹配任一花朵定义
	 */
	private boolean matchesFlowerPreference(ItemStack stack, FlowerPreference pref) {
		// flowerTag：先检查物品标签，再检查方块标签（BlockItem 场景）
		if (!pref.flowerTag().isEmpty()) {
			ResourceLocation tagId = ResourceLocation.parse(pref.flowerTag());
			// 1. 检查物品标签（与 PB isFlowerItem 一致）
			TagKey<Item> itemTag = TagKey.create(BuiltInRegistries.ITEM.key(), tagId);
			if (stack.is(itemTag)) return true;
			// 2. 当物品为 BlockItem 时，同时检查方块标签（与 PB isFlowerBlock / JDTE matchesConfiguredBlockFlower 一致）
			if (stack.getItem() instanceof BlockItem blockItem) {
				TagKey<Block> blockTag = TagKey.create(BuiltInRegistries.BLOCK.key(), tagId);
				if (blockItem.getBlock().defaultBlockState().is(blockTag)) return true;
			}
		}
		// flowerItem：检查具体物品
		if (!pref.flowerItem().isEmpty()) {
			Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(pref.flowerItem()));
			if (stack.is(item)) return true;
		}
		// flowerBlock：检查方块物品（如 sculk_bee 对应 minecraft:sculk_catalyst）
		// 仅当 stack 为 BlockItem 时通过方块注册表 ID 精确比对
		if (!pref.flowerBlock().isEmpty()) {
			if (stack.getItem() instanceof BlockItem blockItem) {
				ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(blockItem.getBlock());
				if (blockId.toString().equals(pref.flowerBlock())) return true;
			}
		}
		// flowerFluid：检查流体桶
		if (!pref.flowerFluid().isEmpty() && stack.getItem() instanceof BucketItem bucket) {
			String fluidId = pref.flowerFluid();
			if (fluidId.startsWith("#")) {
				// 流体标签匹配 — 使用 Holder.is(TagKey) 替代 deprecated 的 Fluid.is(TagKey)
				TagKey<Fluid> fluidTag = TagKey.create(BuiltInRegistries.FLUID.key(),
						ResourceLocation.parse(fluidId.substring(1)));
				if (BuiltInRegistries.FLUID.wrapAsHolder(bucket.content).is(fluidTag)) return true;
			} else {
				// 具体流体匹配
				Fluid fluid = BuiltInRegistries.FLUID.get(ResourceLocation.parse(fluidId));
				if (bucket.content.isSame(fluid)) return true;
			}
		}
		return false;
	}

	/**
	 * 检查喂食器是否有任意花朵
	 * <br/>
	 * 遍历所有喂食槽，任意非空槽位即视为有花朵。
	 * 用于 tick 流程前置检查，避免无花朵时推进生产计时。
	 */
	public boolean hasAnyFlower() {
		for (int i = 0; i < feederSlots.size(); i++) {
			if (!feederSlots.get(i).isEmpty()) {
				return true;
			}
		}
		return false;
	}

	/** NBT key — 喂食槽列表 */
	private static final String NBT_KEY_FEEDER_SLOTS = "productivebeesgenesis_feeder_slots";

	/**
	 * 从喂食槽中随机获取一个匹配指定方块标签的 BlockItem（模块 1 修复）
	 * <br/>
	 * 复刻 PB 原版 FeederBlockEntity.getRandomBlockFromInventory 逻辑：
	 * 遍历喂食槽，筛选 BlockItem 且对应方块在指定标签中的物品，随机返回一个。
	 * 用于 lumber_bee/quarry_bee 等多花蜜脾蜜蜂从喂食槽推断产物。
	 * <p>
	 * 性能：仅在 multi-flower 蜜蜂产出时调用（低频），使用 ThreadLocalRandom 避免竞争。
	 * 喂食槽数量固定（≤60），遍历 O(N) 开销可忽略。
	 *
	 * @param blockTag 方块标签（如 ModTags.LUMBER、ModTags.QUARRY）
	 * @return 匹配的 ItemStack，喂食槽无匹配返回 ItemStack.EMPTY
	 */
	public ItemStack getRandomBlockFromFeeder(TagKey<Block> blockTag) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		Block selected = null;
		int matches = 0;
		for (int i = 0; i < feederSlots.size(); i++) {
			ItemStack stack = feederSlots.get(i).getStack();
			if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem)) continue;
			Block block = ((BlockItem) stack.getItem()).getBlock();
			// 用 BlockState.is 替代废弃的 Block.builtInRegistryHolder().is()
			if (!block.defaultBlockState().is(blockTag)) continue;
			if (random.nextInt(++matches) == 0) selected = block;
		}
		return selected == null ? ItemStack.EMPTY : new ItemStack(selected);
	}

	/**
	 * 从喂食槽中随机获取一个匹配指定物品标签的物品（模块 1 修复）
	 * <br/>
	 * 用于 dye_bee 等蜜蜂从喂食槽推断产物，dye 不一定是 BlockItem（如玫瑰红染料）。
	 *
	 * @param itemTag 物品标签（如 ModTags.DYES）
	 * @return 匹配的 ItemStack，喂食槽无匹配返回 ItemStack.EMPTY
	 */
	public ItemStack getRandomItemFromFeeder(TagKey<Item> itemTag) {
		ThreadLocalRandom random = ThreadLocalRandom.current();
		ItemStack selected = ItemStack.EMPTY;
		int matches = 0;
		for (int i = 0; i < feederSlots.size(); i++) {
			ItemStack stack = feederSlots.get(i).getStack();
			if (stack.isEmpty() || !stack.is(itemTag)) continue;
			if (random.nextInt(++matches) == 0) selected = stack;
		}
		return selected.isEmpty() ? ItemStack.EMPTY : selected.copy();
	}

	/** 为一次 Wanna Bee 生产批次构建有效 PB 琥珀的实体数据快照（委托琥珀工具类） */
	public List<CustomData> getAmberEntityDataSnapshot() {
		return AmberEntityFlowerHelper.getAmberEntityDataSnapshot(feederSlots);
	}

	/** 获取喂食槽列表（IInventorySlot 只读视图，Collections.unmodifiableList 返回原 List 的只读视图而非副本） */
	public List<IInventorySlot> getFeederSlots() {
		return Collections.<IInventorySlot>unmodifiableList(feederSlots);
	}

	/** 获取指定索引的喂食槽 */
	public IInventorySlot getFeederSlot(int index) {
		return feederSlots.get(index);
	}

	/** 获取喂食槽数量 */
	public int getFeederSlotCount() {
		return feederSlotCount;
	}

	/** 获取喂食槽列数 — GUI布局用 */
	public int getFeederCols() {
		return feederCols;
	}

	/** 获取喂食槽行数 — GUI布局用 */
	public int getFeederRows() {
		return feederRows;
	}

	/**
	 * 保存喂食槽到 NBT
	 * <br/>
	 * 每个槽位序列化为 CompoundTag（含 Item 组件），空槽跳过以减小存档体积。
	 */
	void saveFeederSlots(CompoundTag nbt, HolderLookup.Provider provider) {
		if (feederSlots.isEmpty()) return;
		ListTag list = new ListTag();
		for (int i = 0; i < feederSlots.size(); i++) {
			list.add(feederSlots.get(i).serializeNBT(provider));
		}
		nbt.put(NBT_KEY_FEEDER_SLOTS, list);
	}

	/**
	 * 从 NBT 加载喂食槽
	 * <br/>
	 * 必须在 buildFeederSlots() 之后调用（槽位需已存在）。
	 * 兼容存档中槽位数量少于当前槽位数量（工厂版降级场景），多余槽位保持空。
	 */
	void loadFeederSlots(CompoundTag nbt, HolderLookup.Provider provider) {
		if (feederSlots.isEmpty()) return;
		if (!nbt.contains(NBT_KEY_FEEDER_SLOTS, Tag.TAG_LIST)) return;
		ListTag list = nbt.getList(NBT_KEY_FEEDER_SLOTS, Tag.TAG_COMPOUND);
		for (int i = 0; i < feederSlots.size() && i < list.size(); i++) {
			feederSlots.get(i).deserializeNBT(provider, list.getCompound(i));
		}
	}

}
