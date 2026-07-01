package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

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
 * <b>线程安全</b>：使用 {@link ConcurrentHashMap}，客户端渲染线程单线程访问，
 * 并发集合作为防御性措施。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListBeeInfoCache {

	/** 蜜蜂图标缓存 */
	private final Map<String, ItemStack> iconCache = new ConcurrentHashMap<>();
	/** 蜜蜂显示名称缓存 */
	private final Map<String, Component> displayNameCache = new ConcurrentHashMap<>();
	/** 蜜蜂产物信息缓存 */
	private final Map<String, Component> productInfoCache = new ConcurrentHashMap<>();

	/**
	 * 获取蜜蜂代表图标（带缓存）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 图标 ItemStack，无法解析或世界未加载时返回空栈
	 */
	ItemStack getBeeIcon(String beeTypeId) {
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

	/**
	 * 获取蜜蜂显示名称（带缓存）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 显示名称组件
	 */
	Component getBeeDisplayName(String beeTypeId) {
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

	/**
	 * 获取蜜蜂产物信息（带缓存）
	 *
	 * @param beeTypeId 蜜蜂类型ID字符串
	 * @return 产物信息组件
	 */
	Component getBeeProductInfo(String beeTypeId) {
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
