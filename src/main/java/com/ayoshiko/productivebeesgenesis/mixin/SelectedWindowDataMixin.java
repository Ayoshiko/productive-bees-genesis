package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.config.WindowPositionConfigSection;
import com.ayoshiko.productivebeesgenesis.inventory.CustomWindowData;
import mekanism.common.inventory.container.SelectedWindowData.WindowPosition;
import mekanism.common.inventory.container.SelectedWindowData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * SelectedWindowData 窗口位置独立持久化 Mixin（仅客户端加载）
	 * <br/>
	 * 为标记了 customSaveName 的 {@link SelectedWindowData} 实例提供独立的持久化机制，
	 * 将位置和固定状态保存到 PB 自己的客户端配置中，避免与 MEK 原版窗口共享 saveName。
	 * <p>
	 * <b>解决的问题</b>：PB 升级窗口使用 {@code WindowType.UPGRADE}（与 MEK 原版升级窗口相同），
	 * 两者 {@code equals()} 返回 true，共享 MEK 配置中 "upgrade" 的持久化数据，
	 * 导致固定一个窗口会联动影响另一个。
	 * <p>
	 * <b>原理</b>：
	 * <ul>
	 *   <li>拦截 {@code getLastPosition}：customSaveName 非 null 时先 flush 未保存变更，再从 PB 配置读取</li>
	 *   <li>拦截 {@code updateLastPosition}：customSaveName 非 null 时写入 PB 配置（防抖 save）</li>
	 *   <li>customSaveName 为 null 时不干预，MEK 原有逻辑正常执行</li>
	 * </ul>
	 * <p>
	 * <b>防抖机制</b>：窗口拖拽时 updateLastPosition 每像素移动触发一次，
	 * 直接调用 {@code ModConfigSpec.save()} 会导致高频磁盘 IO。
	 * 采用 dirty 标记 + 500ms 防抖：拖拽中每 500ms 最多 save 一次，
	 * 拖拽结束后下次 getLastPosition 调用时（窗口重新打开）flush 未保存变更。
	 * <p>
	 * <b>线程安全</b>：{@code getLastPosition} 和 {@code updateLastPosition} 仅在客户端渲染线程调用，
	 * dirty/lastSaveTime 静态字段无需同步。
	 */
@Mixin(value = SelectedWindowData.class, remap = false)
public abstract class SelectedWindowDataMixin implements CustomWindowData {

	/** 自定义持久化 saveName — null 时使用 MEK 原有逻辑 */
	@Unique
	private String productivebeesgenesis$customSaveName = null;

	/**
	 * 待保存标记 — true 表示有未写入磁盘的位置变更。
	 * <br/>
	 * <b>全局共享原因</b>：所有 {@link SelectedWindowData} 实例共享同一份 dirty 标记，
	 * 避免每实例存储导致内存浪费。线程安全由客户端渲染线程单线程访问保证（见类级 Javadoc）。
	 */
	@Unique
	private static boolean productivebeesgenesis$dirty = false;

	/**
	 * 上次成功 save 的时间戳（毫秒）。
	 * <br/>
	 * <b>全局共享原因</b>：所有 {@link SelectedWindowData} 实例共享同一份防抖时间戳，
	 * 避免每实例存储导致内存浪费。线程安全由客户端渲染线程单线程访问保证（见类级 Javadoc）。
	 */
	@Unique
	private static long productivebeesgenesis$lastSaveTime = 0L;

	/** 防抖间隔（毫秒）— 拖拽中每 500ms 最多 save 一次 */
	@Unique
	private static final long productivebeesgenesis$SAVE_DEBOUNCE_MS = 500L;

	@Override
	public void productivebeesgenesis$setCustomSaveName(String saveName) {
		this.productivebeesgenesis$customSaveName = saveName;
	}

	@Override
	public String productivebeesgenesis$getCustomSaveName() {
		return this.productivebeesgenesis$customSaveName;
	}

	/**
	 * 拦截 getLastPosition — customSaveName 非 null 时先 flush 未保存变更，再从 PB 配置读取
	 * <br/>
	 * 配置未加载或 saveName 未注册时安全降级为默认值（Integer.MAX_VALUE, false）。
	 */
	@Inject(method = "getLastPosition", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$getCustomLastPosition(CallbackInfoReturnable<WindowPosition> cir) {
		if (productivebeesgenesis$customSaveName == null) return;
		// 读取前 flush 未保存的变更，保证窗口重新打开时位置不丢失
		productivebeesgenesis$flushIfDirty();
		WindowPosition position = productivebeesgenesis$readFromConfig(productivebeesgenesis$customSaveName);
		cir.setReturnValue(position);
	}

	/**
	 * 拦截 updateLastPosition — customSaveName 非 null 时保存到 PB 配置（防抖 save）
	 * <br/>
	 * 配置未加载或 saveName 未注册时安全跳过（不保存）。
	 */
	@Inject(method = "updateLastPosition", at = @At("HEAD"), cancellable = true, require = 1)
	private void productivebeesgenesis$updateCustomLastPosition(int x, int y, boolean pinned, CallbackInfo ci) {
		if (productivebeesgenesis$customSaveName == null) return;
		productivebeesgenesis$writeToConfig(productivebeesgenesis$customSaveName, x, y, pinned);
		ci.cancel();
	}

	/**
	 * 从 PB 客户端配置读取窗口位置
	 * <br/>
	 * null 守卫：配置未加载时安全降级为默认值。
	 *
	 * @param saveName 窗口持久化键名
	 * @return 窗口位置（未找到时返回默认值）
	 */
	@Unique
	private static WindowPosition productivebeesgenesis$readFromConfig(String saveName) {
		try {
			if (ModConfig.CLIENT == null) return productivebeesgenesis$defaultPosition();
			WindowPositionConfigSection.Entry entry = ModConfig.CLIENT.windowPositions.getEntry(saveName);
			if (entry == null) return productivebeesgenesis$defaultPosition();
			return new WindowPosition(entry.x.get(), entry.y.get(), entry.pinned.get());
		} catch (Exception e) {
			return productivebeesgenesis$defaultPosition();
		}
	}

	/**
	 * 将窗口位置写入 PB 客户端配置（防抖 save）
	 * <br/>
	 * null 守卫：配置未加载时安全跳过。
	 * <p>
	 * <b>防抖</b>：设置 dirty=true，仅距上次 save 超过 {@link #productivebeesgenesis$SAVE_DEBOUNCE_MS}
	 * 时才真正调用 {@code ModConfigSpec.save()}。未保存的变更由下次 {@link #productivebeesgenesis$flushIfDirty}
	 * （getLastPosition 调用前）flush。
	 *
	 * @param saveName 窗口持久化键名
	 * @param x        X 坐标
	 * @param y        Y 坐标
	 * @param pinned   是否固定
	 */
	@Unique
	private static void productivebeesgenesis$writeToConfig(String saveName, int x, int y, boolean pinned) {
		try {
			if (ModConfig.CLIENT == null) return;
			WindowPositionConfigSection.Entry entry = ModConfig.CLIENT.windowPositions.getEntry(saveName);
			if (entry == null) return;
			boolean changed = false;
			if (entry.x.get() != x) { entry.x.set(x); changed = true; }
			if (entry.y.get() != y) { entry.y.set(y); changed = true; }
			if (entry.pinned.get() != pinned) { entry.pinned.set(pinned); changed = true; }
			if (changed) {
				productivebeesgenesis$dirty = true;
				long now = System.currentTimeMillis();
				// 防抖：距离上次 save 超过阈值才真正写盘
				if (now - productivebeesgenesis$lastSaveTime > productivebeesgenesis$SAVE_DEBOUNCE_MS) {
					ModConfig.CLIENT_SPEC.save();
					productivebeesgenesis$lastSaveTime = now;
					productivebeesgenesis$dirty = false;
				}
			}
		} catch (Exception e) {
			// 配置保存失败不影响主流程
			ProductiveBeesGenesis.LOGGER.warn("窗口位置写入配置失败", e);
		}
	}

	/**
	 * Flush 未保存的变更 — 由 getLastPosition 调用前触发
	 * <br/>
	 * 拖拽结束后窗口关闭，下次打开窗口时 getLastPosition 会先调用此方法，
	 * 保证最后一次位置变更被持久化。
	 */
	@Unique
	private static void productivebeesgenesis$flushIfDirty() {
		if (!productivebeesgenesis$dirty) return;
		try {
			if (ModConfig.CLIENT == null) return;
			ModConfig.CLIENT_SPEC.save();
			productivebeesgenesis$lastSaveTime = System.currentTimeMillis();
			productivebeesgenesis$dirty = false;
		} catch (Exception e) {
			// 配置保存失败不影响主流程
			ProductiveBeesGenesis.LOGGER.warn("窗口位置 flush 配置失败", e);
		}
	}

	@Unique
	private static WindowPosition productivebeesgenesis$defaultPosition() {
		return new WindowPosition(Integer.MAX_VALUE, Integer.MAX_VALUE, false);
	}
}
