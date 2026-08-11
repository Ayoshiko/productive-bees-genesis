package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.mek.IMultiFluidTankHost;

/**
	 * 多流体槽 Tab 同步监听器
	 * <br/>
	 * 在 GUI containerTick 中检测 {@link IMultiFluidTankHost#isMultiFluidModeSynced()} 变化,
	 * 动态添加/移除 Tab,解决首次打开 GUI 时同步数据未到达导致 Tab 不显示的竞态问题。
	 * <p>
	 * <b>竞态根因</b>:客户端 GUI 构造(addGuiElements)发生在 Container 同步数据到达之前,
	 * 首次打开 isMultiFluidModeSynced 默认 false → Tab 不添加;
	 * 第二次打开字段保留上次同步值 true → Tab 显示。
	 * <p>
	 * <b>修复原理</b>:watcher 在 containerTick 中持续轮询同步值,检测到 false→true 变化时
	 * 通过 addTab 回调动态添加 Tab,1-2 帧延迟(约 16-33ms)即可显示。
	 *
	 * @since Task 2
	 */
public class MultiFluidTabSyncWatcher {

	/** 上次同步值 — 用于检测变化,初始 false(SINGLE 模式默认) */
	private boolean lastSynced;

	/**
	 * 初始化监听器
	 * <br/>
	 * 在 addGuiElements 末尾调用,记录 GUI 构造期的同步状态作为基准值。
	 * 若构造期已同步(true),原有 addGuiElements 逻辑已添加 Tab,watcher 不会重复添加。
	 *
	 * @param initialSynced 初始同步状态(GUI 构造期的 isMultiFluidModeSynced() 值)
	 */
	public void init(boolean initialSynced) {
		this.lastSynced = initialSynced;
	}

	/**
	 * 每帧检测同步状态变化,触发添加/移除 Tab 回调
	 * <br/>
	 * <b>线程语义</b>:仅限客户端渲染线程调用(containerTick 调用上下文)。
	 *
	 * @param host      多流体槽宿主(提供 isMultiFluidModeSynced 查询)
	 * @param addTab    添加 Tab 回调(false→true 时调用,内部需加防御性 null 检查避免重复添加)
	 * @param removeTab 移除 Tab 回调(true→false 时调用,内部需按严格时序关闭窗口并清理字段)
	 */
	public void tick(IMultiFluidTankHost host, Runnable addTab, Runnable removeTab) {
		boolean current = host.isMultiFluidModeSynced();
		if (current && !lastSynced) {
			// SINGLE → MULTI:动态添加 Tab
			addTab.run();
		} else if (!current && lastSynced) {
			// MULTI → SINGLE:动态移除 Tab 与窗口
			removeTab.run();
		}
		lastSynced = current;
	}
}
