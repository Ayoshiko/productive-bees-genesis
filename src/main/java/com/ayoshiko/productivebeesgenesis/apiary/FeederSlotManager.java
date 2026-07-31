package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.FlowerPreference;

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
import net.minecraft.world.level.material.Fluid;

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

	/** 初始版喂食槽数量（3×3 矩形） */
	public static final int DEFAULT_FEEDER_SLOT_COUNT = 9;

	/** 初始版喂食槽列数 */
	public static final int DEFAULT_FEEDER_COLS = 3;

	/** 初始版喂食槽行数 */
	public static final int DEFAULT_FEEDER_ROWS = 3;

	/** 喂食槽尺寸（像素） */
	public static final int SLOT_SIZE = 18;

	/** 花朵有效性缓存的初始容量（HashMap 初始桶数，减少 256× 加速下的 rehash） */
	private static final int FLOWER_CACHE_INITIAL_CAPACITY = 64;

	/** 喂食槽数量 */
	private final int feederSlotCount;

	/** 喂食槽列数 */
	private final int feederCols;

	/** 喂食槽行数 */
	private final int feederRows;

	/** 喂食槽列表（FeederInventorySlot 类型，支持 VirtualInventoryContainerSlot 创建） */
	private final List<FeederInventorySlot> feederSlots;

	/**
	 * 花朵有效性缓存（按蜜蜂类型键）。
	 * <br/>
	 * 喂食槽内容变化频率远低于 tick 频率，缓存每只蜜蜂类型的花朵匹配结果可显著降低
	 * 256× 加速场景下每 tick 每只蜜蜂都遍历喂食槽的开销。
	 * <p>
	 * 使用普通 HashMap（非 LinkedHashMap access-order）以避免每次 get 触发
	 * afterNodeAccess 重新链接节点的开销（Spark 显示此处 23.68ms HashMap.get 热点）。
	 * 容量增长时通过 {@link #invalidateFlowerCache()} 在喂食槽变化时主动清空。
	 */
	private final Map<ResourceLocation, Boolean> flowerValidityCache = new HashMap<>(FLOWER_CACHE_INITIAL_CAPACITY);

	/** 上次失效缓存的版本号（监听器递增触发失效） */
	private int flowerCacheVersion = 0;

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
	 * 精确匹配逻辑参考 PB ConfigurableBee.isFlowerItem：
	 * <ul>
	 *   <li>flowerTag：检查 ItemStack 是否在物品标签中</li>
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
		flowerValidityCache.clear();
		flowerCacheVersion++;
	}

	/** 当前花朵缓存版本号（供外部缓存层判断失效） */
	public int getFlowerCacheVersion() {
		return flowerCacheVersion;
	}

	private boolean computeHasValidFlower(ResourceLocation beeTypeKey) {
		FlowerPreference pref = BeeInfoHelper.getFlowerPreference(beeTypeKey);

		// 非 blocks 类型或无花朵定义：回退到任意花朵检查（向后兼容）
		if (!FlowerPreference.TYPE_BLOCKS.equals(pref.flowerType()) || !pref.hasFlowerDefinition()) {
			return hasAnyFlower();
		}

		// 精确匹配：遍历喂食槽检查是否有匹配的花朵物品
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
	 * 检查物品栈是否匹配花朵偏好
	 * <br/>
	 * 参考 PB ConfigurableBee.isFlowerItem 的匹配逻辑：
	 * <ol>
	 *   <li>flowerTag：ItemStack.is(TagKey&lt;Item&gt;)</li>
	 *   <li>flowerItem：ItemStack.is(Item)</li>
	 *   <li>flowerBlock：BlockItem 对应方块的注册表 ID 精确匹配</li>
	 *   <li>flowerFluid：BucketItem.content 匹配流体或流体标签</li>
	 * </ol>
	 * 任一匹配即返回 true。不检查 inverseFlower（与 PB isFlowerItem 行为一致）。
	 *
	 * @param stack 待检查的物品栈
	 * @param pref  花朵偏好
	 * @return true 如果物品匹配任一花朵定义
	 */
	private boolean matchesFlowerPreference(ItemStack stack, FlowerPreference pref) {
		// flowerTag：检查物品标签
		if (!pref.flowerTag().isEmpty()) {
			TagKey<Item> tag = TagKey.create(BuiltInRegistries.ITEM.key(),
					ResourceLocation.parse(pref.flowerTag()));
			if (stack.is(tag)) return true;
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
