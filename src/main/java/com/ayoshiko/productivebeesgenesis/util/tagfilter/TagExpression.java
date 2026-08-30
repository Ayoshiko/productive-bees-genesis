package com.ayoshiko.productivebeesgenesis.util.tagfilter;

/**
 * 标签过滤表达式的抽象语法树节点。
 * <p>
 * 求值只依赖 {@link TagCandidate} 抽象（DIP）：AST 完全不认识 Minecraft 的
 * 标签系统，因此可用纯 JVM 单测覆盖全部逻辑分支。
 * <p>
 * 所有实现均为不可变 record，天然线程安全，可跨 tick 缓存复用。
 */
public sealed interface TagExpression {

	/** 求值：候选物品是否满足本表达式。 */
	boolean test(TagCandidate candidate);

	/** AST 节点总数，供服务端做复杂度上限校验。 */
	int nodeCount();

	/** 字面量：匹配候选的任一标签 id，或候选自身的物品 id。 */
	record Literal(TagPattern pattern) implements TagExpression {
		@Override
		public boolean test(TagCandidate candidate) {
			return candidate.matches(pattern);
		}

		@Override
		public int nodeCount() {
			return 1;
		}
	}

	/** 逻辑非 {@code !}。 */
	record Not(TagExpression operand) implements TagExpression {
		@Override
		public boolean test(TagCandidate candidate) {
			return !operand.test(candidate);
		}

		@Override
		public int nodeCount() {
			return 1 + operand.nodeCount();
		}
	}

	/** 逻辑与 {@code &}，短路求值。 */
	record And(TagExpression left, TagExpression right) implements TagExpression {
		@Override
		public boolean test(TagCandidate candidate) {
			return left.test(candidate) && right.test(candidate);
		}

		@Override
		public int nodeCount() {
			return 1 + left.nodeCount() + right.nodeCount();
		}
	}

	/** 逻辑或 {@code |}，短路求值。 */
	record Or(TagExpression left, TagExpression right) implements TagExpression {
		@Override
		public boolean test(TagCandidate candidate) {
			return left.test(candidate) || right.test(candidate);
		}

		@Override
		public int nodeCount() {
			return 1 + left.nodeCount() + right.nodeCount();
		}
	}

	/** 逻辑异或 {@code ^}。 */
	record Xor(TagExpression left, TagExpression right) implements TagExpression {
		@Override
		public boolean test(TagCandidate candidate) {
			return left.test(candidate) ^ right.test(candidate);
		}

		@Override
		public int nodeCount() {
			return 1 + left.nodeCount() + right.nodeCount();
		}
	}
}
