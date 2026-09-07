package com.ayoshiko.productivebeesgenesis.mek;

import mekanism.api.Action;
import mekanism.api.inventory.IInventorySlot;
import net.minecraft.world.item.ItemStack;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 离心机本地输出槽放置规划器 — 先纯模拟、再一次性写入，并给出「为什么放不下」的诊断。
 * <br/>
 * 从 {@link PbRecipeFlusher} 抽出，遵循单一职责原则：只回答「这批产物能往三个物理输出槽
 * 里放多少」，不涉及流体、输入扣除、AE 直输与 pending 记账。
 * <p>
 * 关键诊断（决定上层是「减半重试」还是「延迟提交」）：
 * <ul>
 *   <li>{@link #placedAll()} — 全部产物都能写入，可走原子提交</li>
 *   <li>{@link #hasUnplaceableType()} — 存在某种产物<b>连一格空间都找不到</b>
 *       （三槽被其它种类占满）。此时减半批量永远不会成功，必须延迟提交</li>
 *   <li>{@link #maxDeferredPerType()} / {@link #slotCapacity()} — 单种产物需要延迟的最大数量
 *       与单槽容量，供上层把批量压到「隐形缓冲不超过一个输出槽」的规模</li>
 * </ul>
 * <p>
 * 性能：所有数组按实例复用（每进程一个 {@link PbRecipeFlusher}），
 * 模拟阶段每槽最多查询一次 {@code getLimit}，写入阶段零拷贝（{@code setStack}/{@code growStack}）。
 * <p>
 * 线程安全：服务端 tick 单线程执行，无需同步。
 */
final class PbOutputPlacementPlanner {

	/** 物理输出槽上限（主+副1+副2），与 {@link PbRecipeContext} 的三槽契约一致。 */
	static final int MAX_SLOTS = 3;

	/** 槽位容量不可知时的兜底延迟预算（一个原版栈）。 */
	private static final int FALLBACK_SLOT_CAPACITY = 64;

	/** 产物模板快照初始容量（多模组整合包中单配方产物可达十余种）。 */
	private static final int INITIAL_TEMPLATE_CAPACITY = 8;

	// ===== 槽位模拟状态（复用） =====
	private final ItemStack[] simStacks = new ItemStack[MAX_SLOTS];
	private final int[] simCounts = new int[MAX_SLOTS];
	private final int[] simLimits = new int[MAX_SLOTS];
	private final int[] addAmounts = new int[MAX_SLOTS];
	private final ItemStack[] setTemplates = new ItemStack[MAX_SLOTS];

	/**
	 * 槽位 → 产物模板索引（-1 表示本次不写该槽）。
	 * <br/>
	 * 一个槽位只会被一种产物占用（空槽被首个模板认领，非空槽只与同种产物堆叠），
	 * 因此写入后可用该映射把「实际写入量」回写到对应模板，防止槽位截断造成产物丢失。
	 * 记录首个贡献者：配方里存在两条 item/组件完全相同的产物条目时，归属首条即可。
	 */
	private final int[] slotTemplate = new int[MAX_SLOTS];

	// ===== 产物模板快照（复用并按需扩容） =====
	/** 快照而非直接遍历 pendingOutputs：延迟提交需要边写边改该 Map。 */
	private ItemStack[] templates = new ItemStack[INITIAL_TEMPLATE_CAPACITY];
	private int[] requested = new int[INITIAL_TEMPLATE_CAPACITY];
	private int[] placed = new int[INITIAL_TEMPLATE_CAPACITY];
	private int templateCount;

	// ===== 模拟结果 =====
	private boolean placedAll = true;
	private boolean placedAny;
	private boolean unplaceableType;
	private int maxDeferredPerType;
	private int slotCapacity = FALLBACK_SLOT_CAPACITY;

	/**
	 * 快照待写出的产物模板。
	 * <br/>
	 * 优先按配方输出表顺序（稳定、与 JEI 展示一致）；配方表缺失时退回 pending 自身顺序。
	 * 末尾补扫一遍 pending，确保没有条目被漏掉（漏掉等于物品凭空消失）。
	 *
	 * @param orderedTemplates 期望的遍历顺序（配方输出表 keySet 或 pending keySet）
	 * @param pendingOutputs   待写出的产物数量表（identity key）
	 * @return 模板数量
	 */
	int snapshot(Iterable<ItemStack> orderedTemplates, Map<ItemStack, Integer> pendingOutputs) {
		templateCount = 0;
		for (ItemStack template : orderedTemplates) {
			Integer count = pendingOutputs.get(template);
			if (count == null || count <= 0) continue;
			append(template, count);
		}
		// 防御：pending 中若有不在配方输出表里的条目（存档恢复、配方热重载），也必须参与放置
		if (templateCount < pendingOutputs.size()) {
			for (Map.Entry<ItemStack, Integer> entry : pendingOutputs.entrySet()) {
				Integer count = entry.getValue();
				if (count == null || count <= 0) continue;
				if (contains(entry.getKey())) continue;
				append(entry.getKey(), count);
			}
		}
		return templateCount;
	}

	/**
	 * 纯模拟：计算每种产物能写入多少、剩余多少，以及放不下的原因。
	 *
	 * @param slots     输出槽列表（跳过 null 槽位）
	 * @param slotCount 有效槽位数量
	 */
	void simulate(List<IInventorySlot> slots, int slotCount) {
		placedAll = true;
		placedAny = false;
		unplaceableType = false;
		maxDeferredPerType = 0;
		int observedCapacity = 0;

		for (int i = 0; i < slotCount; i++) {
			addAmounts[i] = 0;
			setTemplates[i] = null;
			slotTemplate[i] = -1;
			IInventorySlot slot = slots.get(i);
			ItemStack current = slot == null ? ItemStack.EMPTY : slot.getStack();
			simStacks[i] = current;
			if (current.isEmpty()) {
				simCounts[i] = 0;
				// 空槽的上限依赖具体模板，留到填入时再查
				simLimits[i] = 0;
			} else {
				simCounts[i] = current.getCount();
				int limit = slot.getLimit(current);
				simLimits[i] = limit;
				if (limit > observedCapacity) observedCapacity = limit;
			}
		}

		for (int t = 0; t < templateCount; t++) {
			ItemStack template = templates[t];
			int remaining = requested[t];
			int written = 0;
			boolean sawSpace = false;
			for (int i = 0; i < slotCount && remaining > 0; i++) {
				IInventorySlot slot = slots.get(i);
				if (slot == null) continue;
				ItemStack simStack = simStacks[i];
				if (simStack.isEmpty()) {
					int limit = slot.getLimit(template);
					if (limit > observedCapacity) observedCapacity = limit;
					if (limit <= 0) continue;
					sawSpace = true;
					int canFit = Math.min(remaining, limit);
					simStacks[i] = template;
					simCounts[i] = canFit;
					simLimits[i] = limit;
					addAmounts[i] += canFit;
					setTemplates[i] = template;
					if (slotTemplate[i] < 0) slotTemplate[i] = t;
					remaining -= canFit;
					written += canFit;
				} else if (simStack.getItem() == template.getItem()
						&& ItemStack.isSameItemSameComponents(simStack, template)) {
					int space = simLimits[i] - simCounts[i];
					if (space <= 0) continue;
					sawSpace = true;
					int canFit = Math.min(remaining, space);
					simCounts[i] += canFit;
					addAmounts[i] += canFit;
					if (slotTemplate[i] < 0) slotTemplate[i] = t;
					remaining -= canFit;
					written += canFit;
				}
			}
			placed[t] = written;
			if (written > 0) placedAny = true;
			if (remaining > 0) {
				placedAll = false;
				if (remaining > maxDeferredPerType) maxDeferredPerType = remaining;
				// 没有任何槽位能承载该种产物 —— 减少批量也无济于事
				if (!sawSpace) unplaceableType = true;
			}
		}
		slotCapacity = observedCapacity > 0 ? observedCapacity : FALLBACK_SLOT_CAPACITY;
	}

	/**
	 * 按模拟结果写入槽位（零拷贝）。
	 * <br/>
	 * 必须使用 {@code slot.setStack/growStack} 而非直接改 {@link ItemStack}：
	 * {@code IInventorySlot} 契约要求经槽位方法触发 {@code onContentsChanged}，
	 * 否则输出槽标志位会陈旧。每次写入前后分别声明与回填增量缓存。
	 * <p>
	 * 写入后回读实际数量：若槽位实现对数量做了截断（自定义 limit/监听器改写），
	 * 把差额退回对应模板的「未写出量」，避免 pending 记账多扣导致产物丢失。
	 *
	 * @param slots        输出槽列表
	 * @param slotCount    有效槽位数量
	 * @param slotIdxMap   列表索引 → 物理槽索引（0=主，1=副1，2=副2）
	 * @param context      PB 上下文（声明写入 + 增量刷新标志位）
	 * @param processIndex 进程索引
	 * @return true 表示实际写入量与规划完全一致
	 */
	boolean execute(List<IInventorySlot> slots, int slotCount, int[] slotIdxMap,
			PbRecipeContext context, int processIndex) {
		boolean exact = true;
		for (int i = 0; i < slotCount; i++) {
			if (addAmounts[i] <= 0) continue;
			IInventorySlot slot = slots.get(i);
			if (slot == null) continue;
			ItemStack current = slot.getStack();
			boolean empty = current.isEmpty();
			ItemStack template = setTemplates[i];
			if (empty && template == null) continue; // 防御：空槽缺模板不写
			int before = empty ? 0 : current.getCount();
			context.productivebeesgenesis$expectOutputSlotChange();
			if (empty) {
				slot.setStack(template.copyWithCount(addAmounts[i]));
			} else {
				slot.growStack(addAmounts[i], Action.EXECUTE);
			}
			context.productivebeesgenesis$updateSlotOnly(processIndex, slotIdxMap[i], slot);
			ItemStack actual = slot.getStack();
			int written = Math.max(0, (actual.isEmpty() ? 0 : actual.getCount()) - before);
			if (written >= addAmounts[i]) continue;
			exact = false;
			int shortfall = addAmounts[i] - written;
			int templateIndex = slotTemplate[i];
			if (templateIndex >= 0 && templateIndex < templateCount) {
				placed[templateIndex] = Math.max(0, placed[templateIndex] - shortfall);
			}
		}
		return exact;
	}

	// ===== 结果查询 =====

	/** 全部产物均已规划写入（可原子提交） */
	boolean placedAll() {
		return placedAll;
	}

	/** 至少写入了一部分产物（延迟提交的前提） */
	boolean placedAny() {
		return placedAny;
	}

	/** 存在某种产物在当前槽位布局下完全无处安放（减半批量无效） */
	boolean hasUnplaceableType() {
		return unplaceableType;
	}

	/** 单种产物需要延迟的最大数量（0 表示无延迟） */
	int maxDeferredPerType() {
		return maxDeferredPerType;
	}

	/** 观测到的单槽最大容量，作为延迟量预算基准 */
	int slotCapacity() {
		return slotCapacity;
	}

	int templateCount() {
		return templateCount;
	}

	ItemStack templateAt(int index) {
		return templates[index];
	}

	int requestedAt(int index) {
		return requested[index];
	}

	int placedAt(int index) {
		return placed[index];
	}

	private void append(ItemStack template, int count) {
		ensureCapacity(templateCount + 1);
		templates[templateCount] = template;
		requested[templateCount] = count;
		placed[templateCount] = 0;
		templateCount++;
	}

	private boolean contains(ItemStack template) {
		for (int i = 0; i < templateCount; i++) {
			if (templates[i] == template) return true;
		}
		return false;
	}

	private void ensureCapacity(int need) {
		if (need <= templates.length) return;
		int newSize = Math.max(need, templates.length * 2);
		templates = Arrays.copyOf(templates, newSize);
		requested = Arrays.copyOf(requested, newSize);
		placed = Arrays.copyOf(placed, newSize);
	}
}
