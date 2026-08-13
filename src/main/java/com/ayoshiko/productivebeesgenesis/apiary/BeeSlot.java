package com.ayoshiko.productivebeesgenesis.apiary;

import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;

/**
	 * 蜜蜂居住槽位数据模型
	 * <br/>
	 * 复用 PB 原生 Occupant NBT 格式存储蜜蜂完整数据（beeData），
	 * 同时维护生产计时、状态、进度等机械蜂箱专属字段。
	 * <p>
	 * 设计原则：
	 * <ul>
	 *   <li>单一职责：仅承载单只蜜蜂的运行时状态，不涉及 tick 逻辑或槽位管理</li>
	 *   <li>脏标志优化：setter 写入时仅在值实际变化时设置 dirty=true，
	 *       避免冗余的 NBT 同步与 setChanged 调用（Task 16.4 性能优化）</li>
	 * </ul>
	 * <p>
	 * 线程安全（volatile 字段 + dirty 同步点并发模型）：
	 * <ul>
	 *   <li>所有可变状态字段（beeData / ticksInHive / minOccupationTicks / hasNectar /
	 *       state / progress）均声明为 {@code volatile}，依赖 volatile 的跨线程可见性
	 *       保证服务端 tick 线程的写入对 NBT 序列化线程可见</li>
	 *   <li>getter / setter 不使用 {@code synchronized}，避免单线程 tick 主循环中
	 *       每只蜜蜂产生 100+ 次冗余 monitorenter / monitorexit（详见 spec Task 3）</li>
	 *   <li>仅在 {@link #consumeDirty()} 序列化点使用 {@code synchronized(this)} 保护
	 *       "读取 dirty → 写入 false" 原子性，防止 NBT 同步线程与 tick 线程并发调用
	 *       导致脏标志丢失</li>
	 *   <li>{@code dirty} 字段本身也声明为 {@code volatile}，与 synchronized 协同保证
	 *       可见性</li>
	 *   <li>{@code cachedFlowerValidTick} / {@code cachedFlowerValid} 已是 volatile，
	 *       仅在同一 server tick 线程内读写，volatile 足够</li>
	 * </ul>
	 */
public class BeeSlot {

	/** 蜜蜂完整 NBT（复用 PB 原生 Occupant 格式，包含 EntityType、EntityTag 等） */
	private volatile @Nullable CompoundTag beeData;

	/** 已在蜂箱内 tick 数 */
	private volatile int ticksInHive;

	/** 需要的最小 tick（受升级影响，由 ApiaryUpgradeHandler 计算） */
	private volatile int minOccupationTicks;

	/**
	 * 基础最小 occupation ticks（不受升级影响的原始值）
	 * <br/>
	 * 模块1修复：分离 base 与 adjusted，避免 adjustedMinTicks 被回写后
	 * 下一 tick 再次作为 base 乘以倍率导致指数衰减。
	 * 0 表示未初始化（fallback 到配置默认值 cachedProcessingTime）。
	 */
	private volatile int baseMinOccupationTicks;

	/** 是否采集到蜜（影响产出种类） */
	private volatile boolean hasNectar;

	/** 当前蜜蜂状态（驱动 tick 流程与 GUI 状态灯） */
	private volatile BeeState state;

	/** 生产进度 0.0~1.0（供 GUI 状态灯/进度条渲染） */
	private volatile float progress;

	/** 脏标志位 — 优化 NBT 同步，仅在值实际变化时置 true */
	private volatile boolean dirty;

	/**
	 * 花朵有效性缓存：每 tick 缓存当前蜜蜂类型的花朵是否有效，避免重复调用 feederManager.hasValidFlower
	 * <br/>
	 * 使用 {@code -1} 初始 tick 标记未缓存；与 {@link #cachedFlowerValidTick} 配合：
	 * tick 匹配时为 cache hit，否则 cache miss。
	 * <p>
	 * <b>线程安全</b>：写者（tick 主线程）+ 读者（API）均在同一 server tick 线程访问，
	 * GUI 线程仅通过 NBT 同步（不读此字段），故使用 {@code volatile} 足够。
	 * <p>
	 * <b>类型</b>：使用 {@code long} 而非 {@code int}，避免长期运行服务器上
	 * {@code level.getGameTime()}（返回 long）强转 int 时溢出导致缓存命中率异常。
	 */
	private volatile long cachedFlowerValidTick = -1L;
	private volatile boolean cachedFlowerValid;

	/**
	 * F5: 缓存的 productivity 基因纯度
	 * <br/>
	 * -1.0f 表示未缓存，[0.0, 1.0] 为有效值。首次调用 {@link #getProductivityPurity} 时解析并缓存，
	 * {@link #setBeeData} 时重置为 -1.0f。volatile 保证跨线程可见性。
	 */
	private volatile float cachedProductivityPurity = -1.0f;

	/**
	 * 消费缓存的花朵有效性（cache miss 返回 {@code null}，避免哨兵值歧义）
	 * <br/>
	 * processBeeSlots 在循环内对每只蜜蜂调用：同 tick 内同蜜蜂 cache hit，
	 * 避免每次都调用 {@code feederManager.hasValidFlower(beeTypeKey)} 访问 LinkedHashMap。
	 *
	 * @param currentTick 当前 server tick（{@code level.getGameTime()}）
	 * @return 缓存的花朵有效性，cache miss 返回 {@code null}
	 */
	@Nullable
	public Boolean consumeCachedFlowerValid(long currentTick) {
		if (cachedFlowerValidTick == currentTick) {
			return cachedFlowerValid;
		}
		return null;
	}

	/**
	 * 写入缓存的花朵有效性
	 *
	 * @param currentTick 当前 server tick
	 * @param valid       花朵是否有效
	 */
	public void setCachedFlowerValid(long currentTick, boolean valid) {
		cachedFlowerValid = valid;
		cachedFlowerValidTick = currentTick;
	}

	/**
	 * 构造空蜜蜂槽
	 */
	public BeeSlot() {
		this.beeData = null;
		this.ticksInHive = 0;
		this.minOccupationTicks = 0;
		this.baseMinOccupationTicks = 0;
		this.hasNectar = false;
		this.state = BeeState.IDLE;
		this.progress = 0.0f;
		this.dirty = false;
	}

	// ===== Getter / Setter =====

	/** 获取蜜蜂完整 NBT 数据（可能为 null 表示空槽） */
	public @Nullable CompoundTag getBeeData() {
		return beeData;
	}

	/**
	 * 设置蜜蜂完整 NBT 数据
	 * <br/>
	 * 仅在新数据引用不同时标记 dirty，避免无变化的重复写入触发冗余同步。
	 *
	 * @param beeData 蜜蜂 NBT（null 表示清空槽位）
	 */
	public void setBeeData(@Nullable CompoundTag beeData) {
		// 引用相等或均为 null 时跳过，避免冗余 dirty 标记
		if (this.beeData == beeData) return;
		// 内容比较：若 NBT 数据实质相同则跳过（防止 copy() 产生的新引用误触发）
		if (this.beeData != null && beeData != null && this.beeData.equals(beeData)) return;
		this.beeData = beeData;
		// F5: 蜜蜂更换后重置 productivity 纯度缓存
		this.cachedProductivityPurity = -1.0f;
		this.dirty = true;
	}

	public int getTicksInHive() {
		return ticksInHive;
	}

	/**
	 * 设置已居住 tick 数
	 * <br/>
	 * 仅在值实际变化时标记 dirty，减少无变化的 tick 递增产生的同步开销。
	 */
	public void setTicksInHive(int ticksInHive) {
		if (this.ticksInHive == ticksInHive) return;
		this.ticksInHive = ticksInHive;
		this.dirty = true;
	}

	public int getMinOccupationTicks() {
		return minOccupationTicks;
	}

	public void setMinOccupationTicks(int minOccupationTicks) {
		if (this.minOccupationTicks == minOccupationTicks) return;
		this.minOccupationTicks = minOccupationTicks;
		this.dirty = true;
	}

	/**
	 * 获取基础最小 occupation ticks（不受升级影响的原始值）
	 * <br/>
	 * 模块1修复：tick 处理器使用此值作为 base，避免读到已被倍率放大的 adjusted 值。
	 * 返回 0 表示未初始化，调用方应 fallback 到配置默认值。
	 *
	 * @return 基础最小 tick 数，0 表示未初始化
	 */
	public int getBaseMinOccupationTicks() {
		return baseMinOccupationTicks;
	}

	/**
	 * 设置基础最小 occupation ticks
	 * <br/>
	 * 仅在值实际变化时标记 dirty，与 {@link #setMinOccupationTicks} 行为一致。
	 *
	 * @param baseMinOccupationTicks 基础最小 tick 数（0 表示未初始化）
	 */
	public void setBaseMinOccupationTicks(int baseMinOccupationTicks) {
		if (this.baseMinOccupationTicks == baseMinOccupationTicks) return;
		this.baseMinOccupationTicks = baseMinOccupationTicks;
		this.dirty = true;
	}

	public boolean hasNectar() {
		return hasNectar;
	}

	public void setHasNectar(boolean hasNectar) {
		if (this.hasNectar == hasNectar) return;
		this.hasNectar = hasNectar;
		this.dirty = true;
	}

	public BeeState getState() {
		return state;
	}

	public void setState(BeeState state) {
		if (this.state == state) return;
		this.state = state;
		this.dirty = true;
	}

	public float getProgress() {
		return progress;
	}

	public void setProgress(float progress) {
		// 钳制到 0.0~1.0 范围，防止 GUI 渲染异常
		float clamped = Math.max(0.0f, Math.min(1.0f, progress));
		// 浮点数比较使用 Float.compare 处理 NaN/精度
		if (Float.compare(this.progress, clamped) == 0) return;
		this.progress = clamped;
		this.dirty = true;
	}

	/**
	 * 检查并清除脏标志
	 * <br/>
	 * {@code synchronized} 保证 "读取 dirty → 写入 false" 的原子性，
	 * 防止 NBT 同步线程与 tick 线程并发调用导致脏标志丢失。
	 * 这是本类唯一保留 synchronized 的方法，作为 NBT 序列化路径的同步点。
	 *
	 * @return 之前是否为脏（true 表示需要同步）
	 */
	public synchronized boolean consumeDirty() {
		boolean wasDirty = dirty;
		dirty = false;
		return wasDirty;
	}

	/**
	 * 检查当前是否为脏（不清除标志）
	 *
	 * @return true 表示有未同步的变更
	 */
	public boolean isDirty() {
		return dirty;
	}

	/**
	 * F5: 获取蜜蜂 productivity 基因纯度
	 * <br/>
	 * 从 beeData 的
	 * {@code neoforge:attachments.productivebees:attributes_handler.attributes.productivebees:productivity.purity}
	 * 读取纯度值。解析失败或无属性时返回 0.0f（无加成）。首次解析后缓存，
	 * {@link #setBeeData} 时重置缓存。
	 * <p>
	 * 线程安全：cachedProductivityPurity 为 volatile，单次赋值原子。
	 * 多线程竞争时最坏情况重复解析一次，无正确性问题。
	 *
	 * @return productivity 纯度 [0.0, 1.0]，解析失败返回 0.0
	 */
	public float getProductivityPurity() {
		if (cachedProductivityPurity >= 0.0f) {
			return cachedProductivityPurity;
		}
		cachedProductivityPurity = parseProductivityPurity();
		return cachedProductivityPurity;
	}

	/**
	 * 从 beeData NBT 解析 productivity 纯度
	 * <br/>
	 * NBT 路径：{@code neoforge:attachments → productivebees:attributes_handler → attributes →
	 * productivebees:productivity → purity}
	 * 任一层缺失返回 0.0f，异常时返回 0.0f 不抛出。
	 */
	private float parseProductivityPurity() {
		CompoundTag data = this.beeData;
		if (data == null) return 0.0f;
		try {
			CompoundTag attachments = data.getCompound("neoforge:attachments");
			if (attachments.isEmpty()) return 0.0f;
			CompoundTag handler = attachments.getCompound("productivebees:attributes_handler");
			if (handler.isEmpty()) return 0.0f;
			CompoundTag attributes = handler.getCompound("attributes");
			if (attributes.isEmpty()) return 0.0f;
			CompoundTag productivity = attributes.getCompound("productivebees:productivity");
			if (productivity.isEmpty()) return 0.0f;
			float purity = productivity.getFloat("purity");
			// 截断到 [0.0, 1.0] 防止异常值
			return Math.max(0.0f, Math.min(1.0f, purity));
		} catch (RuntimeException e) {
			return 0.0f;
		}
	}

	/**
	 * 槽位是否为空（无蜜蜂数据）
	 *
	 * @return true 表示空槽可装入新蜜蜂
	 */
	public boolean isEmpty() {
		return beeData == null;
	}

	/**
	 * 重置槽位为空状态
	 * <br/>
	 * 清空蜜蜂数据、计时、状态和进度，使槽位可重新装入蜜蜂。
	 */
	public void clear() {
		this.beeData = null;
		this.ticksInHive = 0;
		this.minOccupationTicks = 0;
		this.baseMinOccupationTicks = 0;
		this.hasNectar = false;
		this.state = BeeState.IDLE;
		this.progress = 0.0f;
		// F5: 清空蜜蜂时重置 productivity 纯度缓存
		this.cachedProductivityPurity = -1.0f;
		this.dirty = true;
	}
}
