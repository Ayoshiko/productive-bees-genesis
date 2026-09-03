package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEItemKey;
import com.ayoshiko.productivebeesgenesis.util.BoundedLruMap;
import net.minecraft.core.HolderLookup;

import java.util.Map;

/**
 * per-tile 的 {@link AEItemKey} → SNBT 指纹缓存。
 * <p>
 * <b>动机</b>：{@link Ae2ItemFingerprint#encode} 每次调用都要跑一遍
 * {@code AEItemKey.toTag(registries)}（Mojang Codec 编码）再 {@code CompoundTag.toString()}
 * （StringTagVisitor 遍历），单次成本远高于一次哈希查找。它出现在两条每 tick 热路径上：
 * <ul>
 *   <li>{@link Ae2OutputCommitter#collectSlot} — 每个非空输出槽一次；</li>
 *   <li>{@link Ae2InputPuller} 抽取前的 pending 条目位检查 — 每个拉取类型一次。</li>
 * </ul>
 * 时间加速下这两处被放大到每真实刻上千次：spark ejYMNQjDf7 中
 * {@code Ae2ItemFingerprint.encode} 在拉取侧 432ms、推送侧 408ms（合计约 1.4% 服务端线程），
 * spark BHSGIz87Uw 中合计约 1.9%。
 * <p>
 * <b>为什么按 key 缓存是安全的</b>：AEItemKey 不可变，其 {@code equals/hashCode} 由
 * item + 数据组件决定，与指纹的编码输入完全一致；同一 {@code HolderLookup.Provider}
 * 下同一 key 的编码结果恒定。provider 变化（换存档/重启）时整表清空。
 * <p>
 * <b>有界性</b>：由 {@link BoundedLruMap#accessOrdered} 提供访问顺序 LRU，超出
 * {@link #MAX_ENTRIES} 时仅淘汰最久未使用的一条。此前的实现是"满即整表清空"，
 * 在物品种类超过上限的大网络里会周期性丢弃全部热条目导致命中率塌陷（清空后所有键都要重新编码）；
 * LRU 让稳定复用的键始终驻留，代价只是每次命中多一次链表节点移动。
 * <p>
 * <b>线程安全</b>：与 {@link Ae2PushBuffers} 其他字段一致，仅服务端 tick 线程访问，
 * 因此使用非同步容器，不引入同步开销。客户端 GUI 走
 * {@link Ae2ItemFingerprint#encode} 原路径，不共享本缓存。
 */
final class Ae2FingerprintCache {

	/** 单台机器指纹条目上限；超出即按 LRU 淘汰最久未使用的一条。 */
	private static final int MAX_ENTRIES = 128;

	/** 访问顺序 LRU：get/put 都会把条目移到最近使用端，淘汰最久未使用的一条。 */
	private final Map<AEItemKey, String> cache = BoundedLruMap.accessOrdered(MAX_ENTRIES);

	/** 上次编码使用的注册表访问器；变化即视为整表失效。 */
	private HolderLookup.Provider registries;

	/**
	 * 返回该 key 的 SNBT 指纹，命中缓存时零编码开销。
	 *
	 * @param key      AE2 物品键，null 返回空串（与 {@link Ae2ItemFingerprint#encode} 一致）
	 * @param provider 注册表访问器，null 返回空串
	 */
	String get(AEItemKey key, HolderLookup.Provider provider) {
		if (key == null || provider == null) return "";
		if (registries != provider) {
			cache.clear();
			registries = provider;
		}
		String cached = cache.get(key);
		if (cached != null) return cached;
		String encoded = Ae2ItemFingerprint.encode(key, provider);
		if (encoded.isEmpty()) return encoded;
		// put 触发 removeEldestEntry，超限时淘汰最久未使用条目（不再整表清空）
		cache.put(key, encoded);
		return encoded;
	}
}
