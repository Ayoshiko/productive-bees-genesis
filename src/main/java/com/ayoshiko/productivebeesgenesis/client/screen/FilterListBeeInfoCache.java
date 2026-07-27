package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 过滤列表屏幕的蜜蜂信息缓存
 * <p>
 * 从 {@link FilterListScreen} 抽取的蜜蜂图标/名称/产物信息缓存逻辑（SRP）：
 * <ul>
 *   <li>按类型ID字符串缓存 ItemStack 图标，避免每帧创建</li>
 *   <li>缓存显示名称翻译键解析结果</li>
 *   <li>缓存产物配方遍历结果</li>
 * </ul>
 * <p>
 * <b>容量限制</b>：基于 LRU {@link LinkedHashMap}，超出 {@link #MAX_CACHE_SIZE} 自动淘汰最久未访问条目，
 * 防止长时间运行内存累积。
 * <p>
 * <b>线程安全</b>：客户端 GUI 主线程访问，使用单一锁对象 {@link #lock} 为跨 map 的复合操作
 * 提供防御性同步，避免未来扩展时引入的潜在并发访问问题。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListBeeInfoCache {

	/** 缓存最大条目数，超出后按 LRU 淘汰最久未访问条目 */
	private static final int MAX_CACHE_SIZE = 256;

	/** 单一锁对象 — 保护跨多个 map 的复合操作（如 clear） */
	private final Object lock = new Object();

	/** 蜜蜂图标缓存（LRU，容量受限） */
	private final Map<String, ItemStack> iconCache =
			new LinkedHashMap<String, ItemStack>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, ItemStack> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			};
	/** 蜜蜂显示名称缓存（LRU，容量受限） */
	private final Map<String, Component> displayNameCache =
			new LinkedHashMap<String, Component>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			};
	/** 蜜蜂产物信息缓存（LRU，容量受限） */
	private final Map<String, Component> productInfoCache =
			new LinkedHashMap<String, Component>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Component> eldest) {
					return size() > MAX_CACHE_SIZE;
				}
			};

	/**
	 * 获取蜜蜂代表图标（带缓存）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 图标 ItemStack，无法解析或世界未加载时返回空栈
	 */
	ItemStack getBeeIcon(String beeTypeId) {
		synchronized (lock) {
			ItemStack cached = iconCache.get(beeTypeId);
			if (cached != null) {
				return cached;
			}
			ResourceLocation beeType = BeeInfoHelper.parseBeeType(beeTypeId);
			if (beeType == null) {
				return ItemStack.EMPTY;
			}
			Level level = Minecraft.getInstance().level;
			if (level == null) {
				return ItemStack.EMPTY;
			}
			ItemStack icon = BeeInfoHelper.resolveBeeIcon(level, beeType);
			iconCache.put(beeTypeId, icon);
			return icon;
		}
	}

	/**
	 * 获取蜜蜂显示名称（带缓存）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 显示名称组件
	 */
	Component getBeeDisplayName(String beeTypeId) {
		synchronized (lock) {
			Component cached = displayNameCache.get(beeTypeId);
			if (cached != null) {
				return cached;
			}
			ResourceLocation beeType = BeeInfoHelper.parseBeeType(beeTypeId);
			if (beeType == null) {
				return Component.literal(beeTypeId);
			}
			Component displayName = BeeInfoHelper.getBeeDisplayName(beeType);
			displayNameCache.put(beeTypeId, displayName);
			return displayName;
		}
	}

	/**
	 * 获取蜜蜂产物信息（带缓存）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 产物信息组件
	 */
	Component getBeeProductInfo(String beeTypeId) {
		synchronized (lock) {
			Component cached = productInfoCache.get(beeTypeId);
			if (cached != null) {
				return cached;
			}
			ResourceLocation beeType = BeeInfoHelper.parseBeeType(beeTypeId);
			if (beeType == null) {
				return Component.empty();
			}
			Level level = Minecraft.getInstance().level;
			Component productInfo = level != null
					? BeeInfoHelper.getBeeProductInfo(level, beeType)
					: Component.empty();
			productInfoCache.put(beeTypeId, productInfo);
			return productInfo;
		}
	}

	/**
	 * 清空所有缓存
	 * <p>
	 * 在屏幕关闭（{@link FilterListScreen#onClose()}）时调用，释放图标/名称/产物缓存占用内存。
	 * 虽 LRU 已限制上限，但屏幕关闭后缓存不再有用，主动清空可立即释放内存。
	 * <p>
	 * 线程安全：使用单一锁对象 {@link #lock} 保护跨多个 map 的清空操作，避免 clear 过程中
	 * 其他线程观察到部分 map 已清空、部分未清空的不一致状态。
	 */
	void clear() {
		synchronized (lock) {
			iconCache.clear();
			displayNameCache.clear();
			productInfoCache.clear();
		}
	}
}
