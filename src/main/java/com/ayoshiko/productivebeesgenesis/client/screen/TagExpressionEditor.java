package com.ayoshiko.productivebeesgenesis.client.screen;

/**
 * 标签选取器与表达式编辑框之间的抽象（DIP）。
 * <p>
 * 选取器只需要「读某一侧表达式 / 写某一侧表达式」两件事，不需要知道对面是
 * {@code GuiTextField} 还是别的实现；宿主窗口负责在写入后触发语法校验与配色刷新。
 */
interface TagExpressionEditor {

	/**
	 * @param blacklist true 取黑名单表达式，false 取白名单表达式
	 * @return 当前文本（永不为 null）
	 */
	String getTagExpression(boolean blacklist);

	/** 覆盖某一侧表达式文本。 */
	void setTagExpression(boolean blacklist, String expression);
}
