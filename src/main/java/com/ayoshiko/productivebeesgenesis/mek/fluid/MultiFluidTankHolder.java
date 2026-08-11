package com.ayoshiko.productivebeesgenesis.mek.fluid;

import mekanism.api.IContentsListener;
import mekanism.api.fluid.IExtendedFluidTank;
import mekanism.common.capabilities.fluid.BasicFluidTank;
import mekanism.common.capabilities.holder.fluid.IFluidTankHolder;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
	 * 多流体槽管理器 — 构造时预分配全部 maxTanks 个空槽
	 * <br/>
	 * <b>设计背景：</b>离心机高并行工厂处理多种蜜脾时,不同蜜脾产出不同流体类型。
	 * 原按需分配导致客户端(1 槽)与服务端(2+槽)槽位数量不一致,引发 MEK DataSlot 索引偏移。
	 * 预分配全部 maxTanks 个空槽确保两端槽位数量始终一致(= maxTanks = tier.processes),
	 * 消除 MEK addContainerTrackers 注册的 SyncableFluidStack 数量差异。
	 * <p>
	 * <b>设计原则：</b>
	 * <ul>
	 *   <li>SRP:仅负责多槽路由与生命周期管理,不涉及配方处理、侧面配置、弹出逻辑</li>
	 *   <li>OCP:槽位分配策略调整仅需扩展本类内部实现</li>
	 *   <li>线程安全:ConcurrentHashMap + CopyOnWriteArrayList + AtomicInteger 防御性并发容器</li>
	 * </ul>
	 * <p>
	 * <b>Task 5 MEK 原生侧面配置集成：</b>实现 {@link IFluidTankHolder},
	 * 通过 {@link #getTanks(Direction)} 暴露所有槽位给 MEK 原生 Ejector。
	 * 路由策略由 {@link MultiFluidSideConfigHandler} 封装,本类仅负责槽位数据暴露。
	 *
	 * @since 1.0.0
	 */
public class MultiFluidTankHolder implements IFluidTankHolder {

	/** 流体类型标识 — Fluid + DataComponentMap 哈希,用作 Map key */
	private record FluidKey(Fluid fluid, int componentsHash) {

		/**
		 * 从 FluidStack 构造 FluidKey
		 * <br/>
		 * 使用 {@link FluidStack#getComponents()} 的 hashCode 区分同流体不同组件
		 * (如不同 NBT 的蜂蜜),与 {@link FluidStack#isSameFluidSameComponents} 语义一致。
		 *
		 * @param stack 流体栈
		 * @return 流体类型标识
		 */
		static FluidKey of(FluidStack stack) {
			return new FluidKey(stack.getFluid(), stack.getComponents().hashCode());
		}
	}

	/**
	 * 按流体类型索引的槽位映射(防御性并发容器)
	 * <br/>
	 * v2.0.9: 改为 Map<FluidKey, List<IExtendedFluidTank>>，允许一种流体占用多个槽（溢出场景）
	 * 原单槽映射导致同种类流体满载后无法分配新槽，被迫返回 null 阻塞产出
	 */
	private final Map<FluidKey, List<IExtendedFluidTank>> tanksByFluidKey = new ConcurrentHashMap<>();

	/** 按预分配顺序排列的槽位列表(构造时预分配 maxTanks 个,固定不变) */
	private final List<IExtendedFluidTank> tanksInOrder = new CopyOnWriteArrayList<>();

	/**
	 * 已映射槽位反向索引 — O(1) 判断槽位是否已分配
	 * <br/>
	 * v2.0.9: 替代原 createTankIfNeeded 中的 tanksByFluidKey.values().contains(tank) O(N) 扫描
	 * 在 17 进程工厂（maxTanks=17）下，每次分配从 O(17) 降至 O(1)
	 */
	private final Set<IExtendedFluidTank> mappedTanks = ConcurrentHashMap.newKeySet();

	/** 不可变视图 — 避免每次 getTanks() 创建新 ArrayList */
	private final List<IExtendedFluidTank> unmodifiableTanksView;

	/** 最大槽位数(构造时传入,等于预分配数量) */
	private final int maxTanks;

	/** 单槽容量(mB) */
	private final int tankCapacity;

	/** 槽位内容变更监听器(由外部 TileEntity 传入) */
	private final IContentsListener listener;

	/** 未映射空槽数量 — O(1) 判断 isTypeMismatch,分配空槽时递减 */
	private final AtomicInteger emptyTankCount;

	/**
	 * 每种流体类型最大占用槽位数
	 * <br/>
	 * v2.0.9: 防止高产出流体（如蜂蜜）占用所有槽位，为其他流体预留空位
	 * 配置值 0 表示自动计算为 Math.max(1, maxTanks / 2)
	 */
	private final int maxTanksPerFluid;

	/**
	 * 构造多流体槽管理器,预分配全部 maxTanks 个空槽
	 * <br/>
	 * 预分配确保客户端/服务端槽位数量始终一致(= maxTanks),
	 * 消除 MEK addContainerTrackers 注册的 SyncableFluidStack 数量差异导致的 DataSlot 索引偏移。
	 * <p>
	 * v2.0.9: 新增 maxTanksPerFluidConfig 参数，支持每种流体类型槽位配额
	 *
	 * @param maxTanks              最大槽位数(预分配数量,= tier.processes)
	 * @param tankCapacity          单槽容量(mB)
	 * @param listener              槽位内容变更监听器
	 * @param maxTanksPerFluidConfig 每种流体最大槽位数配置（0=自动计算 maxTanks/2）
	 */
	public MultiFluidTankHolder(int maxTanks, int tankCapacity, IContentsListener listener,
			int maxTanksPerFluidConfig) {
		if (maxTanks < 1) {
			throw new IllegalArgumentException("maxTanks 必须 >= 1，实际: " + maxTanks);
		}
		if (tankCapacity < 0) {
			throw new IllegalArgumentException("tankCapacity 必须 >= 0，实际: " + tankCapacity);
		}
		this.maxTanks = maxTanks;
		this.tankCapacity = tankCapacity;
		this.listener = listener;
		this.emptyTankCount = new AtomicInteger(maxTanks);
		// v2.0.9: 解析 maxTanksPerFluid 配置，0 表示自动计算为 maxTanks/2
		this.maxTanksPerFluid = (maxTanksPerFluidConfig <= 0)
				? Math.max(1, maxTanks / 2)
				: maxTanksPerFluidConfig;
		// 预分配 maxTanks 个空槽,直接使用原始 listener(预分配后槽位固定,无需脏标记机制)
		// output 模式:可提取不可外部插入,符合离心机输出槽语义
		for (int i = 0; i < maxTanks; i++) {
			tanksInOrder.add(BasicFluidTank.output(tankCapacity, listener));
		}
		this.unmodifiableTanksView = Collections.unmodifiableList(tanksInOrder);
	}

	/**
	 * 返回适合插入指定流体的槽
	 * <br/>
	 * v2.0.9 路由策略:
	 * <ol>
	 *   <li>若已有同类型槽(FluidKey 匹配),返回有空间的已映射槽</li>
	 *   <li>已映射槽都满了,检查 maxTanksPerFluid 配额后分配新槽</li>
	 *   <li>若无同类型槽,检查配额后分配新槽</li>
	 *   <li>若无可用空槽或配额已满,返回 null</li>
	 * </ol>
	 * <p>
	 * <b>线程安全：</b>快路径无锁查询(ConcurrentHashMap),慢路径 synchronized 保护原子检查-分配。
	 *
	 * @param stack 待插入流体(仅取类型信息,不修改)
	 * @return 目标槽;若类型不匹配且无空槽返回 null
	 */
	@Nullable
	public IExtendedFluidTank getTankForInsert(FluidStack stack) {
		if (stack.isEmpty()) {
			return null;
		}
		FluidKey key = FluidKey.of(stack);
		// 快路径:无锁查询,命中则返回有空间的已映射槽
		List<IExtendedFluidTank> existing = tanksByFluidKey.get(key);
		if (existing != null) {
			// 优先返回有空间的已映射槽
			for (IExtendedFluidTank tank : existing) {
				if (tank.getFluidAmount() < tank.getCapacity()) {
					return tank;
				}
			}
			// 已映射槽都满了，检查配额后分配新槽
			if (countTanksForFluid(key) < maxTanksPerFluid) {
				return createTankIfNeeded(key);
			}
			return null; // 配额已满
		}
		// 慢路径:从预分配空槽中分配,synchronized 防止并发下重复分配
		return createTankIfNeeded(key);
	}

	/**
	 * 为本 tick 活跃配方预留一个流体类型槽位。
	 * 已有该类型映射时不扩容，避免预扫描把同一种流体占满全部槽位。
	 */
	public void reserveTankForType(FluidStack stack) {
		if (stack.isEmpty()) return;
		FluidKey key = FluidKey.of(stack);
		List<IExtendedFluidTank> existing = tanksByFluidKey.get(key);
		if (existing == null || existing.isEmpty()) {
			createTankIfNeeded(key);
		}
	}

	/**
	 * 统计某种流体已映射的槽位数
	 * <br/>
	 * v2.0.9: 用于 maxTanksPerFluid 配额检查，防止高产出流体占用所有槽位
	 *
	 * @param key 流体类型标识
	 * @return 已映射槽位数（0 表示无映射）
	 */
	public int countTanksForFluid(FluidKey key) {
		List<IExtendedFluidTank> tanks = tanksByFluidKey.get(key);
		return tanks != null ? tanks.size() : 0;
	}

	/**
	 * 从预分配空槽中分配一个映射到新 FluidKey(synchronized 保护原子检查-分配)
	 * <br/>
	 * v2.0.9: 使用 mappedTanks 反向索引替代 tanksByFluidKey.values().contains(tank) O(N) 扫描
	 * 在 17 进程工厂（maxTanks=17）下，每次分配从 O(17) 降至 O(1)
	 * <p>
	 * <b>线程安全：</b>synchronized 保护"检查-分配-注册"原子操作,防止并发下同一 FluidKey 分配到不同槽。
	 *
	 * @param key 流体类型标识
	 * @return 分配的空槽;若无可用空槽返回 null
	 */
	private synchronized IExtendedFluidTank createTankIfNeeded(FluidKey key) {
		// 双重检查:进入锁后再次确认(防止并发下其他线程已分配)
		List<IExtendedFluidTank> existing = tanksByFluidKey.get(key);
		if (existing != null && !existing.isEmpty()) {
			// 已有映射，返回第一个有空间的槽
			for (IExtendedFluidTank tank : existing) {
				if (tank.getFluidAmount() < tank.getCapacity()) {
					return tank;
				}
			}
		}
		// 遍历预分配槽位找第一个未映射空槽（O(1) via mappedTanks）
		for (IExtendedFluidTank tank : tanksInOrder) {
			if (tank.getFluidAmount() == 0 && !mappedTanks.contains(tank)) {
				// List 结构：添加到 FluidKey 对应的 List 中
				// v2.0.9: 使用 CopyOnWriteArrayList 替代 ArrayList,防止 reclaimEmptyTanks 的 removeIf
				// 与 getTankForInsert 快路径的 for-each 遍历并发修改触发 ConcurrentModificationException
				tanksByFluidKey.computeIfAbsent(key, k -> new CopyOnWriteArrayList<>()).add(tank);
				mappedTanks.add(tank);
				emptyTankCount.decrementAndGet();
				return tank;
			}
		}
		// 无可用空槽(所有预分配槽位均已映射)
		return null;
	}

	/**
	 * 返回所有预分配槽列表
	 * <br/>
	 * 供 Ejector / Ae2FluidPusher 遍历弹出。返回不可变视图,调用方修改会抛出
	 * {@link UnsupportedOperationException};由于 tanksInOrder 构造后结构固定,
	 * 视图内容与内部状态实时同步。第 0 个槽为主槽,配合
	 * {@link MultiFluidSideConfigHandler#ejectToSide} 路由策略。
	 *
	 * @return 槽位列表的不可变视图(按预分配顺序,第 0 个为主槽)
	 */
	@NotNull
	public List<IExtendedFluidTank> getTanks() {
		return unmodifiableTanksView;
	}

	/**
	 * IFluidTankHolder 接口实现 — 按侧面返回槽列表
	 * <br/>
	 * 多槽模式下所有侧面均返回全部槽位(侧面过滤由上层 ConfigHolder 处理)。
	 * MEK 原生 Ejector 通过 IProxiedSlotInfo.FluidProxy 调用本方法动态获取槽列表。
	 *
	 * @param side 侧面方向(null 表示内部访问)
	 * @return 槽位列表
	 */
	@NotNull
	@Override
	public List<IExtendedFluidTank> getTanks(@Nullable Direction side) {
		return getTanks();
	}

	/**
	 * 返回第 0 个槽(向后兼容 fluidOutputTank())
	 *
	 * @return 第 0 个槽;无槽位时返回 null
	 */
	@Nullable
	public IExtendedFluidTank getPrimaryTank() {
		return tanksInOrder.isEmpty() ? null : tanksInOrder.get(0);
	}

	/**
	 * 获取主槽(第 0 个槽),永不返回 null
	 * <br/>
	 * 预分配后 tanksInOrder 永不为空,直接返回第 0 个槽。
	 * 保留 synchronized 防御性并发保护(与 createTankIfNeeded 同锁)。
	 *
	 * @return 主槽(永不返回 null)
	 */
	@NotNull
	public synchronized IExtendedFluidTank getOrCreatePrimaryTank() {
		return tanksInOrder.get(0);
	}

	/**
	 * 检查是否存在类型不匹配且无空槽可分配
	 * <br/>
	 * v2.0.9: 改用 List 结构判断，containsKey 仅检查 key 存在，需额外检查 List 非空
	 * O(1) 判断:无同类型槽(或 List 为空)且未映射空槽数为 0 时返回 true
	 *
	 * @param stack 待检查流体
	 * @return true 若类型不匹配且无空槽可分配
	 */
	public boolean isTypeMismatch(FluidStack stack) {
		if (stack.isEmpty()) {
			return false;
		}
		FluidKey key = FluidKey.of(stack);
		// v2.0.9: List 结构下 containsKey 可能为 true 但 List 为空（回收后）
		List<IExtendedFluidTank> tanks = tanksByFluidKey.get(key);
		if (tanks != null && !tanks.isEmpty()) {
			for (IExtendedFluidTank tank : tanks) {
				if (tank.getFluidAmount() < tank.getCapacity()) {
					return false;
				}
			}
			// 匹配槽全部满载且已达到单流体配额时，同样是稳定阻塞状态。
			// 及早返回可避免高并行下每 tick 重做批量输出模拟和二分回退。
			return tanks.size() >= maxTanksPerFluid || emptyTankCount.get() == 0;
		}
		return emptyTankCount.get() == 0;
	}

	/**
	 * 返回当前预分配槽位数
	 *
	 * @return 槽位数(= maxTanks)
	 */
	public int getTankCount() {
		return tanksInOrder.size();
	}

	/**
	 * 返回最大槽位数
	 *
	 * @return 最大槽位数
	 */
	public int getMaxTanks() {
		return maxTanks;
	}

	/**
	 * 返回未映射空槽数量
	 * <br/>
	 * v2.0.9: 供 MultiFluidTankHostHelper.canAllocateNewFluidTank 使用
	 * 修复 BUG #1：原实现检查 getTankCount() < getMaxTanks()，预分配后永远为 false
	 *
	 * @return 未映射空槽数量
	 */
	public int getEmptyTankCount() {
		return emptyTankCount.get();
	}

	/**
	 * 回收空槽映射（v2.0.9 修复 BUG #2）
	 * <br/>
	 * <b>原误解澄清：</b>原注释说"预分配后槽位固定不可回收，回收会导致槽位数量不一致"。
	 * 实际上回收的是 tanksByFluidKey 中的映射关系（FluidKey → Tank），不是从 tanksInOrder 中移除槽位。
	 * tanksInOrder 始终保持 maxTanks 个槽位，DataSlot 偏移不会发生。回收映射后，空槽可以被其他流体类型重新使用。
	 * <p>
	 * <b>线程安全：</b>synchronized 保护回收过程，防止与 getTankForInsert 并发冲突
	 */
	public synchronized void reclaimEmptyTanks() {
		// 回收 tanksByFluidKey 中的空映射（tanksInOrder 槽位固定不变）
		var it = tanksByFluidKey.entrySet().iterator();
		while (it.hasNext()) {
			var entry = it.next();
			List<IExtendedFluidTank> tanks = entry.getValue();
			// CopyOnWriteArrayList.removeIf 线程安全,遍历快照执行移除
			// 即使 getTankForInsert 快路径正在无锁遍历同一 List,也基于快照不会抛 ConcurrentModificationException
			tanks.removeIf(tank -> {
				if (tank.getFluidAmount() == 0) {
					mappedTanks.remove(tank);  // 同步移除反向索引
					emptyTankCount.incrementAndGet();
					return true;
				}
				return false;
			});
			// 若 List 为空，移除整个 FluidKey 条目
			if (tanks.isEmpty()) {
				it.remove();
			}
		}
	}

	// ===== NBT 序列化(扳手拆卸持久化)— 委托给 MultiFluidTankNbtCodec =====

	/**
	 * 将所有非空槽位的 FluidStack 序列化到 NBT
	 * <br/>
	 * 委托给 {@link MultiFluidTankNbtCodec#writeToNBT},保持封装性。
	 * 空槽跳过不保存,减少 NBT 大小。
	 *
	 * @param nbt      目标 NBT(写入到 {@code NBT_KEY_MULTI_FLUID_TANKS} 键下)
	 * @param provider 注册表访问器(FluidStack.save 需要)
	 */
	public synchronized void writeToNBT(CompoundTag nbt, HolderLookup.Provider provider) {
		MultiFluidTankNbtCodec.writeToNBT(this, nbt, provider);
	}

	/**
	 * 从 NBT 反序列化并恢复槽位内容
	 * <br/>
	 * 委托给 {@link MultiFluidTankNbtCodec#readFromNBT}。
	 * 预分配的槽位结构保留,仅清空内容和映射,通过 getTankForInsert 路由到空槽恢复流体。
	 *
	 * @param nbt      源 NBT(从 {@code NBT_KEY_MULTI_FLUID_TANKS} 键读取)
	 * @param provider 注册表访问器(FluidStack.parseOptional 需要)
	 */
	public synchronized void readFromNBT(CompoundTag nbt, HolderLookup.Provider provider) {
		MultiFluidTankNbtCodec.readFromNBT(this, nbt, provider);
	}

	// ===== Package-private getters 供 MultiFluidTankNbtCodec 访问内部字段 =====

	/** 供 NbtCodec 访问预分配槽位列表(用于遍历序列化/清空内容) */
	List<IExtendedFluidTank> getTanksInOrderForCodec() {
		return tanksInOrder;
	}

	/** 供 NbtCodec 访问流体映射(用于 clear 重置映射) */
	Map<FluidKey, List<IExtendedFluidTank>> getTanksByFluidKeyForCodec() {
		return tanksByFluidKey;
	}

	/** 供 NbtCodec 访问空槽计数器(用于 set 重置为 maxTanks) */
	AtomicInteger getEmptyTankCountForCodec() {
		return emptyTankCount;
	}
}
