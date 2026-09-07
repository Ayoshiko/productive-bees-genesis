package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.BeeConversionQueries;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper.FlowerPreference;
import cy.jdkdigital.productivebees.init.ModTags;
import mekanism.api.IContentsListener;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.BooleanSupplier;


/**
	 * 喂食器槽位管理器
	 * <br/>
	 * 管理喂食器窗口内的喂食槽矩阵，用于放置花朵物品供蜜蜂采集。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅管理喂食槽数据结构与判定编排，不涉及蜜蜂生产逻辑或 tick 处理；
	 *       具体匹配规则拆到 {@link BlockFlowerMatcher}（blocks 类）、
	 *       {@link AmberEntityFlowerHelper}（entity_types 类）与 {@link FeederTagSampler}（产物抽样）</li>
	 *   <li>开闭原则：花朵有效性检测通过 {@link #hasValidFlower} 按 PB 花朵配方系统精确匹配（Task E-2）</li>
	 * </ul>
	 * <p>
	 * 阶段五改造：喂食槽数量改为构造参数传入，支持工厂版动态数量（初始版9=3×3，
	 * 工厂版按 ceil(max(蜂蜂数,9)/3)*3 计算，最高 60 槽 3×20），保留 DEFAULT_FEEDER_SLOT_COUNT 作为初始版默认值。
	 * <p>
	 * 逐格禁用：每个槽位可被玩家单独禁用（见 {@link FeederInventorySlot#isActive()}），
	 * 禁用格的物品不参与花朵匹配、转化与多花蜜蜂产物推断，等价于"该格物品对应的蜜蜂产出停用"。
	 * 全部扫描路径统一以 {@code slot.isActive()} 为判据，避免遗漏某条通路造成语义不一致。
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
	 * per-tile 转化开关查询（DIP：依赖抽象而非持有 TileEntity 引用）
	 * <br/>
	 * 默认 {@code false} 与蜂箱字段默认值一致；由 {@link TileEntityMekApiary} 构造时注入
	 * {@code this::isFeederConversionEnabled}。开关关闭时「转化原料算有效花朵」通路必须失效，
	 * 否则玩家只放转化原料（如末影龙蜜蜂的黑曜石）也会被判定为有花朵而照常采蜜产蜜脾。
	 */
	private BooleanSupplier conversionEnabledSupplier = () -> false;

	/** 上次判定时的转化开关状态 — 开关翻转需失效花朵缓存 */
	private boolean lastConversionEnabled = false;

	/**
	 * 喂食槽状态版本号 — 槽位内容或禁用标志任一变化即递增
	 * <br/>
	 * 与 {@link FlowerValidityCache#version()} 区别开来的原因：后者还会因配方重载 / 转化开关翻转
	 * 而递增（那两者不改变槽位内容），把它当作"内容指纹"会让位掩码与 GUI 统计做无谓重算。
	 * 本字段是纯粹的"喂食槽内容 + 禁用状态"指纹，供下方各缓存与客户端 GUI 统计做失效判定。
	 */
	private int stateVersion;

	/**
	 * {@link #hasAnyFlower()} 结果缓存
	 * <br/>
	 * 转化处理器对每个蜜蜂类型组都会调一次，混养 + 60 槽场景下原本是 O(槽位数 × 组数)；
	 * 按 {@link #stateVersion} 缓存后每次内容变化只算一次。
	 */
	private boolean cachedHasAnyFlower;
	private int cachedHasAnyFlowerVersion = -1;

	/** 逐格禁用状态（标志读写 + 同步位掩码 + NBT 持久化，见 {@link FeederSlotDisableState}） */
	private final FeederSlotDisableState disableState;

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
		// 共享同一个 List 引用：buildFeederSlots 只 clear + add，列表实例本身不会被替换
		this.disableState = new FeederSlotDisableState(feederSlots, feederSlotCount);
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
		refreshFlowerCacheState();
		boolean conversionEnabled = lastConversionEnabled;
		Boolean cached = flowerValidityCache.get(beeTypeKey);
		if (cached != null) {
			return cached;
		}

		boolean result = computeHasValidFlower(beeTypeKey, conversionEnabled);
		flowerValidityCache.put(beeTypeKey, result);
		return result;
	}

	/**
	 * 注入 per-tile 转化开关查询 — 由 {@link TileEntityMekApiary} 构造时调用
	 * <br/>
	 * 用 {@link BooleanSupplier} 而非持有 TileEntity 引用：避免槽位管理器反向依赖方块实体
	 * （DIP + 迪米特法则），也便于工厂版子类复用同一注入路径。
	 */
	public void setConversionEnabledSupplier(BooleanSupplier supplier) {
		if (supplier == null) return;
		this.conversionEnabledSupplier = supplier;
		this.lastConversionEnabled = supplier.getAsBoolean();
		flowerValidityCache.invalidate();
	}




	/**
	 * 失效花朵有效性缓存 — 喂食槽内容或禁用状态变化时调用
	 * <br/>
	 * 由 FeederInventorySlot 的 IContentsListener 在 setStack 变更时调用，
	 * 确保缓存与喂食槽实际内容保持一致；同时推进 {@link #stateVersion}，
	 * 让位掩码缓存、hasAnyFlower 缓存与客户端 GUI 统计缓存一并失效。
	 */
	public void invalidateFlowerCache() {
		flowerValidityCache.invalidate();
		stateVersion++;
	}

	/**
	 * 当前喂食槽状态版本号（内容 + 禁用标志指纹）
	 * <br/>
	 * 供客户端 GUI 缓存统计结果：版本未变时无需重扫 60 个槽位去重花朵种类。
	 */
	public int getStateVersion() {
		return stateVersion;
	}

	// ===== 逐格禁用（判定与编解码委托 FeederSlotDisableState，本类只负责缓存失效与存档标脏） =====

	/** 指定格是否被玩家禁用（索引越界返回 false） */
	public boolean isSlotDisabled(int index) {
		return disableState.isDisabled(index);
	}

	/** 指定格是否处于"有物品但被禁用"状态 — 供 GUI 渲染灰色遮罩使用 */
	public boolean isSlotBlocked(int index) {
		return disableState.isBlocked(index);
	}

	/**
	 * 切换指定格的禁用状态（服务端权威路径）
	 * <br/>
	 * 状态变化后失效花朵缓存：禁用格不再参与花朵匹配，缓存版本号递增会连带失效
	 * {@link ApiaryConversionProcessor} 的饲养板匹配缓存与 {@code BeeSlot} 的逐蜂花朵缓存。
	 *
	 * @param index 槽位索引
	 * @return true 表示状态已改变（调用方据此决定是否 setChanged）
	 */
	public boolean toggleSlotDisabled(int index) {
		if (!disableState.toggle(index)) return false;
		invalidateFlowerCache();
		return true;
	}

	/**
	 * 批量设置全部格子的禁用状态（服务端权威路径，Shift + 点击「禁」按钮）
	 * <br/>
	 * 整批只失效缓存一次，避免 60 次版本号递增导致下游缓存被反复重建。
	 *
	 * @param disabled true = 全部停用，false = 全部恢复
	 * @return true 表示至少有一格状态改变
	 */
	public boolean setAllSlotsDisabled(boolean disabled) {
		if (!disableState.setAll(disabled)) return false;
		invalidateFlowerCache();
		return true;
	}

	/** 禁用位掩码同步字数量 — 供容器 tracker 注册（客户端/服务端必须一致） */
	public int getDisabledWordCount() {
		return disableState.wordCount();
	}

	/** 读取指定同步字的禁用位掩码（服务端 → 客户端，按状态版本号缓存打包结果） */
	public long getDisabledWord(int wordIndex) {
		return disableState.word(wordIndex, stateVersion);
	}

	/**
	 * 写入指定同步字的禁用位掩码（客户端同步回调）
	 * <br/>
	 * 客户端同样需要失效缓存：{@code GuiFeederWindow} 底部提示与信息面板会读取
	 * 生效格子数，缓存不失效会让统计停留在旧值。
	 */
	public void setDisabledWord(int wordIndex, long word) {
		disableState.applyWord(wordIndex, word);
		invalidateFlowerCache();
	}

	/** 当前花朵缓存版本号（供外部缓存层判断失效） */
	public int getFlowerCacheVersion() {
		refreshFlowerCacheState();
		return flowerValidityCache.version();
	}

	/** 在外层版本缓存命中前同步所有会改变花朵语义的状态。 */
	private void refreshFlowerCacheState() {
		int conversionQueriesVersion = BeeConversionQueries.getVersion();
		if (lastConversionQueriesVersion != conversionQueriesVersion) {
			flowerValidityCache.invalidate();
			lastConversionQueriesVersion = conversionQueriesVersion;
		}
		boolean conversionEnabled = conversionEnabledSupplier.getAsBoolean();
		if (lastConversionEnabled != conversionEnabled) {
			flowerValidityCache.invalidate();
			lastConversionEnabled = conversionEnabled;
		}
	}

	private boolean computeHasValidFlower(ResourceLocation beeTypeKey, boolean conversionEnabled) {
		// PB isFlowerItem/isFlowerBlock 语义：转化原料（物品转化 / 方块转化 BlockItem）
		// 对任意花朵类型（blocks/entity_types/Rancher）都是有效花朵，与 flowerType 无关——
		// 必须在 Rancher/实体早退分支之前检查，否则 entity_types 类蜜蜂放入转化原料
		// 会因花朵判定失败导致 pending 清零、转化永不执行。
		// 但该通路只在 per-tile 转化开关开启时成立：开关关闭时转化不会执行，
		// 若仍把转化原料算作有效花朵，蜜蜂会"凭黑曜石采蜜"产出本应靠龙蛋才有的蜜脾。
		if (conversionEnabled
				&& BeeConversionQueries.hasAnyConversionRecipe(beeTypeKey)
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
			FeederInventorySlot slot = feederSlots.get(i);
			if (!slot.isActive()) continue;
			if (BlockFlowerMatcher.matches(slot.getStack(), pref)) {
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
			FeederInventorySlot slot = feederSlots.get(i);
			if (!slot.isActive()) {
				continue;
			}
			if (BeeConversionQueries.hasFeederConversionFlower(beeTypeKey, slot.getStack())) {
				return true;
			}
		}
		return false;
	}

	/**
	 * 检查喂食器是否有任意花朵
	 * <br/>
	 * 遍历所有喂食槽，任意生效（非空且未禁用）槽位即视为有花朵。
	 * 用于 tick 流程前置检查，避免无花朵时推进生产计时。
	 * <p>
	 * 结果按 {@link #stateVersion} 缓存：转化处理器对每个蜜蜂类型组都会调一次，
	 * 混养 60 槽场景下原本是 O(槽位数 × 组数)，缓存后每次内容变化只扫一遍。
	 */
	public boolean hasAnyFlower() {
		if (cachedHasAnyFlowerVersion == stateVersion) {
			return cachedHasAnyFlower;
		}
		boolean any = false;
		for (int i = 0; i < feederSlots.size(); i++) {
			if (feederSlots.get(i).isActive()) {
				any = true;
				break;
			}
		}
		cachedHasAnyFlower = any;
		cachedHasAnyFlowerVersion = stateVersion;
		return any;
	}

	/** NBT key — 喂食槽列表 */
	private static final String NBT_KEY_FEEDER_SLOTS = "productivebeesgenesis_feeder_slots";

	/**
	 * 从喂食槽中随机获取一个匹配指定方块标签的 BlockItem（模块 1 修复）
	 * <br/>
	 * 用于 lumber_bee/quarry_bee 等多花蜜脾蜜蜂从喂食槽推断产物，
	 * 抽样规则委托 {@link FeederTagSampler#randomBlock}（禁用格不参与抽样）。
	 *
	 * @param blockTag 方块标签（如 ModTags.LUMBER、ModTags.QUARRY）
	 * @return 匹配的 ItemStack，喂食槽无匹配返回 ItemStack.EMPTY
	 */
	public ItemStack getRandomBlockFromFeeder(TagKey<Block> blockTag) {
		return FeederTagSampler.randomBlock(feederSlots, blockTag);
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
		return FeederTagSampler.randomItem(feederSlots, itemTag);
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
	 * 逐格禁用状态以独立位掩码键保存（{@link FeederSlotDisableState}），
	 * 不侵入 Mekanism 的槽位 NBT 结构，旧存档缺键时全部按启用处理。
	 */
	void saveFeederSlots(CompoundTag nbt, HolderLookup.Provider provider) {
		if (feederSlots.isEmpty()) return;
		ListTag list = new ListTag();
		for (int i = 0; i < feederSlots.size(); i++) {
			list.add(feederSlots.get(i).serializeNBT(provider));
		}
		nbt.put(NBT_KEY_FEEDER_SLOTS, list);
		disableState.save(nbt);
	}

	/**
	 * 从 NBT 加载喂食槽
	 * <br/>
	 * 必须在 buildFeederSlots() 之后调用（槽位需已存在）。
	 * 兼容存档中槽位数量少于当前槽位数量（工厂版降级场景），多余槽位保持空。
	 * <p>
	 * 禁用位掩码必须在槽位内容之后加载：{@link FeederInventorySlot#onContentsChanged()}
	 * 会在槽位为空时清除禁用标志，顺序颠倒会把刚读出的标志清掉。
	 */
	void loadFeederSlots(CompoundTag nbt, HolderLookup.Provider provider) {
		if (feederSlots.isEmpty()) return;
		if (nbt.contains(NBT_KEY_FEEDER_SLOTS, Tag.TAG_LIST)) {
			ListTag list = nbt.getList(NBT_KEY_FEEDER_SLOTS, Tag.TAG_COMPOUND);
			for (int i = 0; i < feederSlots.size() && i < list.size(); i++) {
				feederSlots.get(i).deserializeNBT(provider, list.getCompound(i));
			}
		}
		disableState.load(nbt);
		invalidateFlowerCache();
	}

}
