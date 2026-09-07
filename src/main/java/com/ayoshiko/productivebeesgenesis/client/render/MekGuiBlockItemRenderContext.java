package com.ayoshiko.productivebeesgenesis.client.render;

/**
 * JEI 拖拽预览的线程局部渲染上下文。
 *
 * <p>该类必须位于普通业务包中，不能放在 {@code mixin.*} 包内，否则 Mixin
 * 类加载器会禁止 Mixin 以外的代码直接引用它。</p>
 */
public final class MekGuiBlockItemRenderContext {

	private static final ThreadLocal<Boolean> JEI_DRAG_RENDER = ThreadLocal.withInitial(() -> false);

	private MekGuiBlockItemRenderContext() {
	}

	public static void beginJeiDragRender() {
		JEI_DRAG_RENDER.set(true);
	}

	public static void endJeiDragRender() {
		JEI_DRAG_RENDER.remove();
	}

	public static boolean isJeiDragRender() {
		return JEI_DRAG_RENDER.get();
	}
}
