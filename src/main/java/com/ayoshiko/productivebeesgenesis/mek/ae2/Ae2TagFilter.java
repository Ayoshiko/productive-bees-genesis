package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagExpressionParser;
import com.ayoshiko.productivebeesgenesis.util.tagfilter.TagFilterSpec;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.Set;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * per-tile AE2 输入标签过滤状态 — 白名单/黑名单表达式的持有者与编译缓存。
 * <p>
 * 职责（SRP）：仅负责表达式文本的持久化、编译与版本号发布；
 * 匹配语义由 {@link TagFilterSpec} 承担，候选枚举由 {@link Ae2TagFilterCache} 承担。
 * <p>
 * <b>为什么独立成类</b>：{@link Ae2InputFilter} 已 700+ 行，且蜜脾种类过滤（位置固定槽位）
 * 与标签表达式过滤是两种完全不同的配置模型，混在一起会同时违反 SRP 与行数约束。
 * <p>
 * <b>Issue #8 类加载安全</b>：本类不引用任何 appeng 类，AE2 未安装时可安全构造。
 * <p>
 * <b>线程安全</b>：{@code spec} 为 volatile，写路径 synchronized（编译 + 发布 + 版本递增原子），
 * 读路径无锁。{@code generation} 使用 {@link AtomicInteger}，供拉取侧缓存做失效判断。
 */
public final class Ae2TagFilter {

	/** 单侧表达式最大长度（与解析器上限一致，网络层复用同一常量校验）。 */
	public static final int MAX_EXPRESSION_LENGTH = TagExpressionParser.MAX_EXPRESSION_LENGTH;

	private static final String KEY_WHITELIST = "w";
	private static final String KEY_BLACKLIST = "b";
	private static final String KEY_INVALID = "invalid_data";

	/** 已编译规格；volatile 一次性发布，避免读到半初始化状态。 */
	private volatile TagFilterSpec spec = TagFilterSpec.EMPTY;
	private volatile Tag invalid;

	/** 配置代号：每次表达式变更递增，供结果缓存整体失效。 */
	private final AtomicInteger generation = new AtomicInteger();

	public TagFilterSpec getSpec() {
		return spec;
	}

	public String getWhitelistSource() {
		return spec.whitelistSource();
	}

	public String getBlacklistSource() {
		return spec.blacklistSource();
	}

	/** 是否存在生效表达式；false 时拉取热路径完全跳过标签匹配。 */
	public boolean isActive() {
		return spec.isActive();
	}

	/** 是否存在语法错误（GUI 提示用；错误侧自动失效，不影响拉取继续工作）。 */
	public boolean hasError() {
		return spec.hasError();
	}

	public int getGeneration() {
		return generation.get();
	}

	/**
	 * 编译并发布一对表达式。
	 *
	 * @return true 表示配置确实发生变化（调用方据此决定 markForSave）
	 */
	public synchronized boolean apply(String whitelistSource, String blacklistSource) {
		if (invalid != null) return false;
		TagFilterSpec current = spec;
		TagFilterSpec compiled = TagFilterSpec.compile(whitelistSource, blacklistSource);
		if (current.whitelistSource().equals(compiled.whitelistSource())
				&& current.blacklistSource().equals(compiled.blacklistSource())) {
			return false;
		}
		spec = compiled;
		generation.incrementAndGet();
		return true;
	}

	/** 清空标签过滤（方块重建/旧存档无数据时使用）。 */
	public synchronized void reset() {
		if (spec == TagFilterSpec.EMPTY && invalid == null) return;
		invalid = null;
		spec = TagFilterSpec.EMPTY;
		generation.incrementAndGet();
	}

	/** 序列化；无配置时不写键，保持存档紧凑。 */
	public void save(CompoundTag tag) {
		Tag raw = invalid;
		if (raw != null) {
			tag.put(KEY_INVALID, raw.copy());
			return;
		}
		TagFilterSpec current = spec;
		if (!current.whitelistSource().isEmpty()) tag.putString(KEY_WHITELIST, current.whitelistSource());
		if (!current.blacklistSource().isEmpty()) tag.putString(KEY_BLACKLIST, current.blacklistSource());
	}

	/** 升级快照只接受 CompoundTag，坏外层经包封保留，正式 NBT 使用 saveRaw。 */
	public Tag saveRaw() {
		Tag raw = invalid;
		if (raw != null) return raw.copy();
		CompoundTag saved = new CompoundTag();
		save(saved);
		return saved;
	}

	public synchronized void load(CompoundTag tag) {
		if (tag.getAllKeys().equals(Set.of(KEY_INVALID))) {
			loadRaw(tag.get(KEY_INVALID));
		} else {
			loadRaw(tag);
		}
	}

	/** 缺省保持空过滤；坏结构和超长原文保留，不能降级为空表达式。 */
	public synchronized void loadRaw(Tag raw) {
		if (raw == null) {
			reset();
			return;
		}
		if (!(raw instanceof CompoundTag tag) || !isReadable(tag)) {
			invalid = raw.copy();
			spec = TagFilterSpec.EMPTY;
			generation.incrementAndGet();
			return;
		}
		invalid = null;
		spec = TagFilterSpec.compile(tag.getString(KEY_WHITELIST), tag.getString(KEY_BLACKLIST));
		generation.incrementAndGet();
	}

	public boolean hasInvalidData() {
		return invalid != null;
	}

	public synchronized boolean retryDecode() {
		if (invalid == null) return true;
		loadRaw(invalid.copy());
		return invalid == null;
	}

	private static boolean isReadable(CompoundTag tag) {
		return Set.of(KEY_WHITELIST, KEY_BLACKLIST).containsAll(tag.getAllKeys())
				&& (!tag.contains(KEY_WHITELIST) || tag.contains(KEY_WHITELIST, Tag.TAG_STRING))
				&& (!tag.contains(KEY_BLACKLIST) || tag.contains(KEY_BLACKLIST, Tag.TAG_STRING))
				&& tag.getString(KEY_WHITELIST).length() <= MAX_EXPRESSION_LENGTH
				&& tag.getString(KEY_BLACKLIST).length() <= MAX_EXPRESSION_LENGTH;
	}
}
