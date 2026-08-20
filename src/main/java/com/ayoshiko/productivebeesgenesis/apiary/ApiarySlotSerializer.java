package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.util.DevLog;
import mekanism.common.inventory.container.MekanismContainer;
import mekanism.common.inventory.container.sync.SyncableBoolean;
import mekanism.common.inventory.container.sync.SyncableByteArray;
import mekanism.common.inventory.container.sync.SyncableEnum;
import mekanism.common.inventory.container.sync.SyncableFloat;
import mekanism.common.inventory.container.sync.SyncableInt;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

/**
	 * 蜜蜂槽序列化器
	 * <br/>
	 * 从 {@link ApiarySlotManager} 拆分，负责蜜蜂槽数组的 NBT 持久化与客户端网络同步：
	 * <ul>
	 *   <li>{@link #saveBeeSlots} / {@link #loadBeeSlots} — 存档序列化/反序列化</li>
	 *   <li>{@link #addContainerTrackers} — 容器追踪器同步蜜蜂状态到客户端</li>
	 *   <li>{@link #serializeBeeData} / {@link #deserializeBeeData} — beeData 字节数组编解码（网络同步用）</li>
	 * </ul>
	 * <p>
	 * 通过组合关系持有 {@link ApiarySlotManager} 引用，访问蜜蜂槽数组。
	 * 无自有可变状态，NBT key 为静态常量。
	 * <p>
	 * 线程安全：序列化在服务端主线程执行；网络同步的 dirty 检查基于数组 hashCode，
	 * SyncableByteArray 内部保证同步线程安全。
	 */
public class ApiarySlotSerializer {

	/** NBT key — 蜜蜂槽数组（带模组前缀避免冲突） */
	public static final String NBT_KEY_BEE_SLOTS = "productivebeesgenesis_apiary_bee_slots";

	/** 空槽位共享的空字节数组 — 不可变语义，客户端据此判断槽位为空 */
	private static final byte[] EMPTY_BEE_DATA = new byte[0];

	/** 所属槽位管理器 — 访问蜜蜂槽数组与数量 */
	private final ApiarySlotManager manager;

	/** per-slot 序列化缓存 — beeData 引用未变时复用字节数组，避免每 gameTick 重复 NBT 压缩（v1.0.2） */
	private byte[][] serializedBeeDataCache;

	/** per-slot 缓存对应的 beeData 引用 — 引用相等即缓存命中 */
	private CompoundTag[] serializedSourceCache;

	/**
	 * 构造蜜蜂槽序列化器
	 *
	 * @param manager 所属槽位管理器
	 */
	ApiarySlotSerializer(ApiarySlotManager manager) {
		this.manager = manager;
	}

	// ===== NBT 序列化 =====

	/**
	 * 保存蜜蜂槽数组到 NBT
	 * <br/>
	 * 使用 ListTag 存储，每个 BeeSlot 序列化为 CompoundTag。
	 * 复用 PB 原生 Occupant 格式存储 beeData（entity_data 字段）。
	 * 空 BeeSlot 跳过以减小存档体积。
	 *
	 * @param nbt 目标 NBT 标签
	 */
	void saveBeeSlots(CompoundTag nbt) {
		ListTag list = new ListTag();
		BeeSlot[] beeSlots = manager.getBeeSlots();
		int beeSlotCount = manager.getBeeSlotCount();
		for (int i = 0; i < beeSlotCount; i++) {
			BeeSlot slot = beeSlots[i];
			if (slot.isEmpty()) continue;
			CompoundTag slotNbt = new CompoundTag();
			// 保存绝对槽位索引：列表会跳过空槽压缩存储，加载时按索引还原
			// 修复：selectedBeeSlot 等绝对索引在压缩/还原后不再错位
			slotNbt.putInt("slot_index", i);
			if (slot.getBeeData() != null) {
				slotNbt.put("entity_data", slot.getBeeData());
			}
			slotNbt.putInt("ticks_in_hive", slot.getTicksInHive());
			slotNbt.putInt("min_occupation_ticks", slot.getMinOccupationTicks());
			// 模块1修复：持久化基础最小 occupation ticks，避免 adjusted 值被回写后下一 tick 再次乘以倍率
			slotNbt.putInt("base_min_occupation_ticks", slot.getBaseMinOccupationTicks());
			slotNbt.putBoolean("has_nectar", slot.hasNectar());
			slotNbt.putString("state", slot.getState().name());
			slotNbt.putFloat("progress", slot.getProgress());
			list.add(slotNbt);
		}
		nbt.put(NBT_KEY_BEE_SLOTS, list);
	}

	/**
	 * 从 NBT 加载蜜蜂槽数组
	 * <br/>
	 * 兼容空槽位（ListTag 长度 < beeSlotCount 时，剩余槽位保持空状态）。
	 * 兼容旧版存档：若 ListTag 长度大于当前 beeSlotCount（如从工厂版降级到初始版），
	 * 仅加载前 beeSlotCount 个槽位，多余数据忽略。
	 *
	 * @param nbt 源 NBT 标签
	 */
	void loadBeeSlots(CompoundTag nbt) {
		BeeSlot[] beeSlots = manager.getBeeSlots();
		int beeSlotCount = manager.getBeeSlotCount();
		// 先清空所有槽位，防止旧数据残留
		for (int i = 0; i < beeSlotCount; i++) {
			beeSlots[i].clear();
		}
		if (!nbt.contains(NBT_KEY_BEE_SLOTS, Tag.TAG_LIST)) return;
		ListTag list = nbt.getList(NBT_KEY_BEE_SLOTS, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag slotNbt = list.getCompound(i);
			// 优先按保存的绝对索引还原（防止压缩存储导致槽位左移、绝对索引错位）；
			// 旧存档无 slot_index 键时回退到顺序填充
			int target = slotNbt.contains("slot_index", Tag.TAG_ANY_NUMERIC)
					? slotNbt.getInt("slot_index") : i;
			if (target < 0 || target >= beeSlotCount) {
				continue;
			}
			BeeSlot slot = beeSlots[target];
			if (slotNbt.contains("entity_data", Tag.TAG_COMPOUND)) {
				// .copy() 防止共享 NBT 引用导致意外修改
				slot.setBeeData(slotNbt.getCompound("entity_data").copy());
			}
			slot.setTicksInHive(slotNbt.getInt("ticks_in_hive"));
			slot.setMinOccupationTicks(slotNbt.getInt("min_occupation_ticks"));
			// 模块1修复：读取基础最小 occupation ticks，含老存档迁移逻辑
			if (slotNbt.contains("base_min_occupation_ticks")) {
				// 新存档：直接读取 base 值
				slot.setBaseMinOccupationTicks(slotNbt.getInt("base_min_occupation_ticks"));
			} else {
				// 老存档迁移：无 base 字段时从 min_occupation_ticks 推断
				// 代码审查修复：上界从配置读取（默认1200），而非硬编码10000
				// 避免被bug污染的较低值（如500）被误迁移为base，导致卸载升级后无法恢复
				int upperBound = 1200; // fallback 默认值
				try {
					upperBound = com.ayoshiko.productivebeesgenesis.config.ModConfig.SERVER.apiaryProcessingTime.get();
				} catch (NullPointerException e) {
					// 配置未加载时使用默认值 1200
				}
				int oldMinTicks = slotNbt.getInt("min_occupation_ticks");
				// 仅当值在合理范围（>0 且 <=配置值）时迁移，否则设为0触发fallback到配置默认值
				slot.setBaseMinOccupationTicks((oldMinTicks > 0 && oldMinTicks <= upperBound) ? oldMinTicks : 0);
			}
			slot.setHasNectar(slotNbt.getBoolean("has_nectar"));
			try {
				slot.setState(BeeState.valueOf(slotNbt.getString("state")));
			} catch (IllegalArgumentException e) {
				// 未知状态名（可能来自未来版本），回退到 IDLE
				DevLog.warn("nbt_serialize", "加载蜜蜂槽位时遇到未知状态: {}", slotNbt.getString("state"));
				slot.setState(BeeState.IDLE);
			}
			slot.setProgress(slotNbt.getFloat("progress"));
		}
	}

	// ===== 网络同步框架 =====

	/**
	 * 添加容器追踪器 — 同步蜜蜂状态到客户端
	 * <br/>
	 * 每只蜜蜂同步：
	 * <ul>
	 *   <li>state（枚举 ordinal）— 状态灯渲染</li>
	 *   <li>progress（float）— 进度条渲染</li>
	 *   <li>hasNectar（boolean）— 状态灯渲染</li>
	 *   <li>beeDataBytes（byte[]）— 蜜蜂完整 NBT，供客户端渲染蜜蜂实体、名称、tooltip</li>
	 *   <li>ticksInHive（int）— tooltip 进度显示</li>
	 *   <li>minOccupationTicks（int）— tooltip 进度显示</li>
	 * </ul>
	 * <p>
	 * beeData 通过 SyncableByteArray 同步其 NBT 字节数组形式，
	 * dirty 检查基于数组 hashCode，仅在蜜蜂装入/取出时触发同步。
	 *
	 * @param container 待注册追踪器的容器
	 */
	void addContainerTrackers(MekanismContainer container) {
		BeeSlot[] beeSlots = manager.getBeeSlots();
		int beeSlotCount = manager.getBeeSlotCount();
		// 惰性初始化 per-slot 序列化缓存（tracker 注册仅在 GUI 打开时执行）
		if (serializedBeeDataCache == null || serializedBeeDataCache.length != beeSlotCount) {
			serializedBeeDataCache = new byte[beeSlotCount][];
			serializedSourceCache = new CompoundTag[beeSlotCount];
		}
		for (int i = 0; i < beeSlotCount; i++) {
			final BeeSlot slot = beeSlots[i];
			final int slotIndex = i;
			// 状态枚举 — 通过 ordinal 同步
			container.track(SyncableEnum.create(
					ApiarySlotSerializer::stateByOrdinal,
					BeeState.IDLE,
					slot::getState,
					slot::setState
			));
			// 生产进度 — 供 GUI 进度条渲染
			container.track(SyncableFloat.create(
					slot::getProgress,
					slot::setProgress
			));
			// 是否有蜜 — 供 GUI 状态灯渲染
			container.track(SyncableBoolean.create(
					slot::hasNectar,
					slot::setHasNectar
			));
			// 蜜蜂完整 NBT（字节数组形式）— 供客户端渲染蜜蜂实体、名称、tooltip
			// v1.0.2：SyncableByteArray 的 dirty 检查每 gameTick 调用 getter，
			// 原实现每 tick 每 slot 执行一次 NbtIo.writeCompressed（49 槽工厂蜂箱
			// = 每 gameTick 49 次 NBT 压缩序列化 + 全数组 hashCode）。
			// BeeSlot 仅在蜜蜂实质变化时更新 beeData 引用，引用相等即复用缓存，
			// 序列化次数从 每 tick×N 降为 仅蜜蜂变化时。
			container.track(SyncableByteArray.create(
					() -> serializeBeeDataCached(slotIndex, slot),
					bytes -> slot.setBeeData(deserializeBeeData(bytes))
			));
			// 已居住 tick 数 — tooltip 进度显示
			container.track(SyncableInt.create(
					slot::getTicksInHive,
					slot::setTicksInHive
			));
			// 最小 occupation ticks — tooltip 进度显示
			container.track(SyncableInt.create(
					slot::getMinOccupationTicks,
					slot::setMinOccupationTicks
			));
		}
	}

	/**
	 * 带引用缓存的 beeData 序列化（网络同步 getter 专用，v1.0.2）
	 * <br/>
	 * {@code BeeSlot.setBeeData} 仅在蜜蜂实质变化（装入/取出/NBT 修改）时更新引用，
	 * 因此引用相等即可安全复用上次序列化结果。空槽位返回共享的 {@link #EMPTY_BEE_DATA}。
	 *
	 * @param slotIndex 槽位索引（缓存数组下标）
	 * @param slot      蜜蜂槽
	 * @return 压缩后的字节数组（缓存实例，调用方不得修改）
	 */
	private byte[] serializeBeeDataCached(int slotIndex, BeeSlot slot) {
		CompoundTag beeData = slot.getBeeData();
		if (beeData == null) return EMPTY_BEE_DATA;
		if (beeData == serializedSourceCache[slotIndex]) {
			return serializedBeeDataCache[slotIndex];
		}
		byte[] serialized = serializeBeeData(beeData);
		serializedSourceCache[slotIndex] = beeData;
		serializedBeeDataCache[slotIndex] = serialized;
		return serialized;
	}

	/**
	 * 将 beeData 序列化为字节数组（用于网络同步）
	 * <br/>
	 * 使用 NbtIo 压缩写入，空数据返回共享空数组（长度为0），客户端据此判断槽位为空。
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @return 压缩后的字节数组，空数据返回共享空数组（不可变语义）
	 */
	private static byte[] serializeBeeData(CompoundTag beeData) {
		if (beeData == null) return EMPTY_BEE_DATA;
		try {
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			NbtIo.writeCompressed(beeData, baos);
			return baos.toByteArray();
		} catch (Exception e) {
			// DevLog 节流日志便于排查（网络同步路径，避免刷屏）
			DevLog.warn("nbt_serialize", "序列化 beeData 失败: {}", e.toString());
			return EMPTY_BEE_DATA;
		}
	}

	/**
	 * 从字节数组反序列化 beeData（用于网络同步）
	 * <br/>
	 * 使用 NbtIo 压缩读取，空数组返回 null（表示空槽位）。
	 * <p>
	 * 安全限制：使用 {@link NbtAccounter#create(long)} 设置 64KB 堆上限，
	 * 防止恶意或损坏的网络数据包构造超大 NBT 导致 OOM。
	 * 超出上限时抛出 RuntimeException，由 catch 块统一处理。
	 *
	 * @param bytes 压缩字节数组
	 * @return 蜜蜂 NBT 数据，空数组返回 null
	 */
	private static CompoundTag deserializeBeeData(byte[] bytes) {
		if (bytes == null || bytes.length == 0) return null;
		try {
			ByteArrayInputStream bais = new ByteArrayInputStream(bytes);
			// 限制 NBT 解压堆内存为 64KB，防止恶意数据导致 OOM
			return NbtIo.readCompressed(bais, NbtAccounter.create(65536L));
		} catch (RuntimeException e) {
			// 捕获 NbtAccounter 超限及反序列化异常，记录 WARN 并返回 null 避免崩溃
			// DevLog 节流日志便于排查（网络同步路径，避免恶意客户端刷屏）
			DevLog.warn("nbt_serialize", "反序列化 beeData 失败（可能超过 64KB 上限）: {}", e.toString());
			return null;
		} catch (Exception e) {
			// DevLog 节流日志便于排查（网络同步路径，避免恶意客户端刷屏）
			DevLog.warn("nbt_serialize", "反序列化 beeData 失败: {}", e.toString());
			return null;
		}
	}

	/**
	 * 通过 ordinal 查找 BeeState（带边界保护）
	 *
	 * @param ordinal 枚举序号
	 * @return 对应的 BeeState，越界返回 IDLE
	 */
	private static BeeState stateByOrdinal(int ordinal) {
		BeeState[] values = BeeState.values();
		return ordinal >= 0 && ordinal < values.length ? values[ordinal] : BeeState.IDLE;
	}
}
