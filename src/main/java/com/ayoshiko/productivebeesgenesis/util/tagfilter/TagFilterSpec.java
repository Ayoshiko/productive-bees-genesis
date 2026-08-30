package com.ayoshiko.productivebeesgenesis.util.tagfilter;

/**
 * 已编译的标签过滤规格 — 白名单表达式 + 黑名单表达式的不可变组合。
 * <p>
 * 语义（与 ExtendedAE-Plus 标签库存 ME 接口一致）：
 * <ul>
 *   <li>白名单为空 → 不限制种类；非空 → 仅表达式为真的物品通过</li>
 *   <li>黑名单为空 → 不排除任何物品；非空 → 表达式为真的物品一律排除</li>
 *   <li>黑名单优先于白名单（先准入再排除）</li>
 * </ul>
 * 字面量既可写标签 id，也可直接写物品 id。
 * <p>
 * 不可变（record + 编译期确定的 AST），可被多线程共享读取；
 * 编译失败的表达式降级为 null，即「该侧不生效」，同时保留 errorKey 供 GUI 提示，
 * 保证一条非法表达式不会让整台机器停止拉取。
 */
public record TagFilterSpec(String whitelistSource, String blacklistSource,
		TagExpression whitelist, TagExpression blacklist,
		String whitelistErrorKey, String blacklistErrorKey) {

	/** 未配置任何标签过滤的空规格（单例，避免每 tick 分配）。 */
	public static final TagFilterSpec EMPTY = new TagFilterSpec("", "", null, null, null, null);

	/** 编译一对表达式文本；任一侧非法时仅该侧失效并记录 errorKey。 */
	public static TagFilterSpec compile(String whitelistSource, String blacklistSource) {
		String white = whitelistSource == null ? "" : whitelistSource.trim();
		String black = blacklistSource == null ? "" : blacklistSource.trim();
		if (white.isEmpty() && black.isEmpty()) return EMPTY;

		TagExpressionParser.Result whiteResult = TagExpressionParser.parse(white);
		TagExpressionParser.Result blackResult = TagExpressionParser.parse(black);
		return new TagFilterSpec(white, black,
				whiteResult.expression(), blackResult.expression(),
				whiteResult.errorKey(), blackResult.errorKey());
	}

	/** 是否存在任一生效的表达式；false 时调用方可完全跳过标签匹配。 */
	public boolean isActive() {
		return whitelist != null || blacklist != null;
	}

	/** 是否存在语法错误（供 GUI 高亮与服务端拒绝保存）。 */
	public boolean hasError() {
		return whitelistErrorKey != null || blacklistErrorKey != null;
	}

	/**
	 * 判定候选是否通过标签过滤。
	 *
	 * @param candidate 候选物品视图；null 视为不通过
	 */
	public boolean allows(TagCandidate candidate) {
		if (!isActive()) return true;
		if (candidate == null) return false;
		if (blacklist != null && blacklist.test(candidate)) return false;
		return whitelist == null || whitelist.test(candidate);
	}
}
