package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberWorkLayout;

/** 对齐成员投影的最坏编码长度；按声明容量计费，装蜂和基因变化不扩大已发布页。 */
final class TerminalMemberPageBudget {
	// 两种回复的封包头均小于 128 字节；余量不分配给成员内容。
	static final int MAX_BYTES = TerminalReply.MAX_BYTES - 128;
	// 标签可为 UTF-8；机器／维度注册 ID 为 ASCII，其余固定字段含可选能力摘要。
	private static final int ROW_BYTES = 3 * TerminalView.TEXT_LIMIT + 2 + 2 * (TerminalView.TEXT_LIMIT + 1) + 64;
	// 蜂种显示可为 UTF-8，喂食 ID 和五项基因有 ASCII 校验；33 字节覆盖其余字段与 UUID。
	private static final int BEE_BYTES = 3 * TerminalView.TEXT_LIMIT + 2 + TerminalView.TEXT_LIMIT + 1
			+ (TerminalBeeGenes.VALUE_LIMIT + 1) * TerminalBeeGenes.FIELDS.size() + 33;

	static int weight(NetworkSelectionSession.Row row) {
		if (!(row instanceof NetworkSelectionSession.MemberRow member)) return 0;
		int slots = MemberWorkLayout.forMachine(member.claim().machine()).beeSlots();
		return weight(Math.max(slots, member.bees().size()));
	}

	static int weight(int slots) {
		return ROW_BYTES + Math.multiplyExact(slots, BEE_BYTES);
	}

	private TerminalMemberPageBudget() { }
}
