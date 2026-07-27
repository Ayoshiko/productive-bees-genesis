package com.ayoshiko.productivebeesgenesis.apiary.client;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.ayoshiko.productivebeesgenesis.apiary.BeeNbtHelper;
import com.ayoshiko.productivebeesgenesis.apiary.BeeSlot;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;

import com.mojang.blaze3d.vertex.PoseStack;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import org.jetbrains.annotations.Nullable;

/**
 * 蜜蜂名称渲染器
 * <br/>
 * 在蜜蜂槽下方常驻显示蜜蜂名称，提供视觉识别。
 * <p>
 * 渲染特性：
 * <ul>
 *   <li>名称位于槽位下方居中，y 偏移 19px（槽高 18 + 1px 间距）</li>
 *   <li>使用 Minecraft 原生字体，9px 高度</li>
 *   <li>带 1px 黑色文字阴影（dropShadow=true）</li>
 *   <li>颜色对应蜜蜂状态（{@link com.ayoshiko.productivebeesgenesis.apiary.BeeState#getColor()}）</li>
 *   <li>长名称自动截断加省略号（超过槽宽 16px 时）</li>
 *   <li>空槽位不渲染</li>
 * </ul>
 * <p>
 * Task 16.6 性能优化：
 * <ul>
 *   <li>添加名称缓存（{@link #nameCache}），按槽位索引缓存解析结果</li>
 *   <li>仅在 beeData 引用变化时重新解析（避免每帧 NBT 解析与翻译键查找）</li>
 *   <li>20 只蜜蜂 × 60fps 场景下，将 1200 次/秒的 NBT 解析降为 0（稳态）</li>
 * </ul>
 * <p>
 * 设计原则：单一职责，仅负责名称渲染，不涉及实体或状态灯渲染。
 * <br/>
 * 线程安全：仅从客户端渲染线程调用，无需同步。
 */
public class BeeNameRenderer {

	/** 槽位尺寸（18×18 像素） */
	private static final int SLOT_SIZE = 18;

	/** 名称 y 偏移（槽高 18 + 1px 间距 = 19） */
	private static final int NAME_Y_OFFSET = 19;

	/** 最大文本宽度（缩放后可用宽度，0.5F缩放×18px=36px等效，但实际渲染宽度限制为18px） */
	private static final int MAX_TEXT_WIDTH = SLOT_SIZE * 2;

	/** 文字缩放比例（0.5F使中文字符约3.5px宽，18px可显示约5个字符） */
	private static final float TEXT_SCALE = 0.5F;

	/** 省略号字符串 */
	private static final String ELLIPSIS = "...";

	/**
	 * 名称缓存条目
	 * <br/>
	 * 记录上次解析时的 beeData 引用（identity 比较）与解析结果 Component。
	 * 当 beeData 引用变化时（蜜蜂装卸），触发重新解析。
	 */
	private static final class NameCacheEntry {
		final CompoundTag beeDataRef;
		@Nullable final Component name;

		NameCacheEntry(CompoundTag beeDataRef, @Nullable Component name) {
			this.beeDataRef = beeDataRef;
			this.name = name;
		}
	}

	/**
	 * 名称缓存 — 按槽位索引缓存解析结果
	 * <br/>
	 * Key: 槽位索引（Integer）
	 * Value: {@link NameCacheEntry}（含 beeData 引用与解析结果）
	 * <p>
	 * 缓存命中条件：beeData 引用与上次相同（identity 比较，O(1)）。
	 * 蜜蜂装卸时 beeData 引用变化，自动触发重新解析。
	 * <p>
	 * 使用 ConcurrentHashMap 遵循项目规范（即使仅客户端渲染线程访问），
	 * 防御性保证线程安全，便于未来扩展。
	 */
	private final Map<Integer, NameCacheEntry> nameCache = new ConcurrentHashMap<>();

	/**
	 * 在蜜蜂槽下方渲染蜜蜂名称
	 * <br/>
	 * 渲染流程：
	 * <ol>
	 *   <li>空槽位跳过并清理对应缓存</li>
	 *   <li>检查名称缓存：beeData 引用未变时直接复用缓存的 Component</li>
	 *   <li>缓存未命中时从 beeData 解析 EntityType，获取本地化名称</li>
	 *   <li>根据 BeeState 获取名称颜色</li>
	 *   <li>截断过长名称（超过 16px 加省略号）</li>
	 *   <li>居中渲染带阴影的文本</li>
	 * </ol>
	 *
	 * @param guiGraphics GUI 图形上下文
	 * @param x           槽位左上角绝对 X 坐标
	 * @param y           槽位左上角绝对 Y 坐标
	 * @param beeSlot     蜜蜂槽数据
	 * @param font        Minecraft 字体实例
	 * @param slotIndex   槽位索引（用于名称缓存，避免每帧 NBT 解析）
	 */
	public void renderName(GuiGraphics guiGraphics, int x, int y, BeeSlot beeSlot, Font font, int slotIndex) {
		if (beeSlot.isEmpty()) {
			// 空槽位清理缓存，防止蜜蜂卸下后残留旧名称
			nameCache.remove(slotIndex);
			return;
		}

		// 解析蜜蜂名称（带缓存，避免每帧 NBT 解析）
		Component name = resolveBeeNameCached(beeSlot, slotIndex);
		if (name == null) return;

		// 截断过长名称（按缩放后等效宽度判断）
		Component display = truncateIfNeeded(font, name);

		// Bug 2修复：使用0.5F缩放渲染，使18px宽度可显示约5个中文字符
		int textWidth = font.width(display);
		// 缩放后实际渲染宽度
		float scaledWidth = textWidth * TEXT_SCALE;
		// 居中位置（基于缩放后宽度）
		float textX = x + (SLOT_SIZE - scaledWidth) / 2.0F;
		float textY = y + NAME_Y_OFFSET;

		// 颜色：BeeState 颜色 + 不透明 ARGB
		int color = 0xFF000000 | beeSlot.getState().getColor();

		// 使用PoseStack缩放渲染
		PoseStack pose = guiGraphics.pose();
		pose.pushPose();
		pose.translate(textX, textY, 0);
		pose.scale(TEXT_SCALE, TEXT_SCALE, 1.0F);
		// 在缩放坐标系下绘制（原点为0,0，已通过translate定位）
		guiGraphics.drawString(font, display, 0, 0, color, true);
		pose.popPose();
	}

	/**
	 * 解析蜜蜂名称（带缓存）
	 * <br/>
	 * 缓存策略：按槽位索引存储上次解析的 beeData 引用与结果。
	 * 当 beeData 引用未变时直接返回缓存，避免每帧重复 NBT 解析。
	 *
	 * @param beeSlot   蜜蜂槽数据
	 * @param slotIndex 槽位索引
	 * @return 蜜蜂显示名称，解析失败返回 null
	 */
	@Nullable
	private Component resolveBeeNameCached(BeeSlot beeSlot, int slotIndex) {
		CompoundTag beeData = beeSlot.getBeeData();
		if (beeData == null) return null;

		// 检查缓存：beeData 引用相同时直接返回缓存结果
		NameCacheEntry cached = nameCache.get(slotIndex);
		if (cached != null && cached.beeDataRef == beeData) {
			return cached.name;
		}

		// 缓存未命中，重新解析
		Component name = resolveBeeName(beeData);
		// 写入缓存（即使 name 为 null 也缓存，避免重复解析失败）
		nameCache.put(slotIndex, new NameCacheEntry(beeData, name));
		return name;
	}

	/**
	 * 从 beeData NBT 解析蜜蜂显示名称
	 * <br/>
	 * 通过 {@link BeeNbtHelper#resolveBeeTypeKey} 解析蜜蜂类型键，
	 * 再通过 {@link BeeInfoHelper#getBeeDisplayName} 获取本地化名称。
	 * 兼容 PB Occupant 格式（"id" 字段）和蜂笼格式（"entity"/"type" 字段）。
	 * 解析失败时返回 null（不渲染名称）。
	 *
	 * @param beeData 蜜蜂 NBT 数据
	 * @return 蜜蜂显示名称，解析失败返回 null
	 */
	@Nullable
	private static Component resolveBeeName(CompoundTag beeData) {
		try {
			ResourceLocation beeType = BeeNbtHelper.resolveBeeTypeKey(beeData);
			if (beeType == null) return null;
			return BeeInfoHelper.getBeeDisplayName(beeType);
		} catch (Exception e) {
			// 解析失败静默跳过，避免日志刷屏
			return null;
		}
	}

	/**
	 * 截断过长名称加省略号
	 * <br/>
	 * 如果名称渲染宽度超过 {@link #MAX_TEXT_WIDTH}，使用 {@link Font#plainSubstrByWidth}
	 * 截断到预留省略号宽度内，并追加 "..."。名称未超长时原样返回。
	 *
	 * @param font Minecraft 字体
	 * @param name 原始名称组件
	 * @return 截断后的名称组件（超长时带省略号）
	 */
	private Component truncateIfNeeded(Font font, Component name) {
		if (font.width(name) <= MAX_TEXT_WIDTH) {
			return name;
		}

		String plainText = name.getString();
		int ellipsisWidth = font.width(ELLIPSIS);
		int maxTextWidth = MAX_TEXT_WIDTH - ellipsisWidth;

		if (maxTextWidth <= 0) {
			// 槽宽过小，仅显示省略号
			return Component.literal(ELLIPSIS);
		}

		// plainSubstrByWidth 返回渲染宽度不超过 maxTextWidth 的最长前缀子串
		String truncated = font.plainSubstrByWidth(plainText, maxTextWidth);
		return Component.literal(truncated + ELLIPSIS);
	}

	/**
	 * 清空名称缓存
	 * <br/>
	 * 在 GUI 关闭或配方重载时调用，防止缓存无限增长与持有过期引用。
	 */
	public void clearCache() {
		nameCache.clear();
	}
}
