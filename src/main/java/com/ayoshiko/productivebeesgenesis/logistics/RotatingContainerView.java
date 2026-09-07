package com.ayoshiko.productivebeesgenesis.logistics;

import java.util.AbstractList;
import java.util.List;

/**
 * 按游戏刻轮转起点的容器视图（零分配）。
 * <p>
 * <b>要解决的问题：</b>大量物流模组对多槽/多罐容器的访问是「从索引 0 开始扫、搬够本次
 * 配额就停」，或者（对流体）调用 {@code drain(int)} —— Mekanism 的实现会锁定
 * <em>第一个非空罐</em>的流体类型。两者的共同后果是：索引靠前的槽/罐被反复清空，
 * 靠后的永远排不上队。高等级离心机工厂有 17 个流体槽、最多 54 个输出槽，
 * 一旦靠后的槽位被占满且抽不走，产线就会因“输出满”停机。
 * <p>
 * <b>做法：</b>把暴露给外部的列表包装成一个下标偏移的视图，偏移量 =
 * {@code gameTime % size}。这样：
 * <ul>
 *   <li>同一游戏刻内偏移量恒定 —— 外部模组「先 {@code getFluidInTank(i)} 读、
 *       再按内容 drain」这类两步操作看到的索引语义保持一致；</li>
 *   <li>跨刻自动轮转 —— 每个槽/罐都会周期性地排到第 0 位，不会被饿死；</li>
 *   <li>偏移量是 {@code gameTime} 的纯函数 —— 与调用次数无关，任何调用方在同一刻
 *       观察到的顺序都相同（时间加速模组同刻多次调用也不会撕裂）。</li>
 * </ul>
 * <p>
 * 视图是<b>轻量包装</b>：不复制元素、不分配数组，{@code get(i)} 为 O(1)；源列表内容
 * 实时可见。轮转是索引置换（双射），因此外部按索引回写/抽取始终命中真实容器。
 * <p>
 * 线程安全：{@code offset} 为 volatile，仅由 {@link #forTick(long)} 写入；
 * 最坏情况下并发读到相邻两刻的偏移量，仍是一个合法置换，不会越界或错位。
 *
 * @param <T> 容器元素类型（{@code IExtendedFluidTank} / {@code IInventorySlot}）
 */
public final class RotatingContainerView<T> extends AbstractList<T> {

	/** 源列表（结构固定，内容可变） */
	private final List<T> source;

	/** 当前轮转偏移量（volatile 保证跨线程可见性） */
	private volatile int offset;

	/**
	 * 包装一个源列表为轮转视图。
	 *
	 * @param source 源列表（不复制，需保证结构长度稳定）
	 */
	public RotatingContainerView(List<T> source) {
		this.source = source;
	}

	/**
	 * 刷新轮转偏移量并返回本视图。
	 * <p>
	 * 偏移量取 {@code gameTime % size}，同刻多次调用结果一致。
	 *
	 * @param gameTime 当前游戏刻（{@code Level#getGameTime}）
	 * @return 本视图（偏移量已对齐到该刻）
	 */
	public List<T> forTick(long gameTime) {
		int size = source.size();
		if (size > 1) {
			int rotated = (int) Math.floorMod(gameTime, size);
			// 仅在变化时写入，避免每次访问都触发 volatile 写（缓存行失效）
			if (rotated != offset) offset = rotated;
		} else if (offset != 0) {
			offset = 0;
		}
		return this;
	}

	@Override
	public T get(int index) {
		int size = source.size();
		if (index < 0 || index >= size) {
			throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
		}
		return source.get((int) Math.floorMod((long) offset + index, size));
	}

	@Override
	public int size() {
		return source.size();
	}
}
