package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.annotation.ParametersAreNonnullByDefault;

import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * FilterListScreen 的剪贴板导入导出工具类
 * <p>
 * 将 JSON 格式导出、剪贴板文本解析与导入校验等无状态操作从屏幕类中剥离。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>SRP — 仅负责剪贴板数据格式转换与校验，不持有状态、不操作 GUI</li>
 *   <li>静态工具方法 — 所有操作无状态，输入决定输出，便于单元测试与复用</li>
 * </ul>
 * <br/>
 * 线程安全：纯函数式工具类，无共享状态，天然线程安全。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
final class FilterListClipboardHelper {

	private FilterListClipboardHelper() {
	}

	// ========== 导出 ==========

	/**
	 * 将蜜蜂类型列表导出为 JSON 数组字符串。
	 * <p>
	 * 例：{@code ["minecraft:bee", "productivebees:iron_bee"]}
	 *
	 * @param beeTypes 蜜蜂类型ID列表
	 * @return JSON 数组格式字符串
	 */
	static String exportToJson(List<String> beeTypes) {
		return beeTypes.stream()
				.map(s -> "\"" + s + "\"")
				.collect(Collectors.joining(", ", "[", "]"));
	}

	// ========== 导入 ==========

	/**
	 * 解析剪贴板文本为原始 token 列表（已清理引号与空白）。
	 * <p>
	 * 支持两种格式：
	 * <ol>
	 *   <li>JSON 数组格式：{@code ["a","b"]}</li>
	 *   <li>逗号/空白分隔格式：{@code a, b} 或 {@code a b}</li>
	 * </ol>
	 *
	 * @param clipboardText 剪贴板原始文本，null 或空白返回空列表
	 * @return 已清理的 token 列表（不含空字符串）
	 */
	static List<String> parseTokens(String clipboardText) {
		if (clipboardText == null || clipboardText.isBlank()) {
			return List.of();
		}
		String trimmed = clipboardText.trim();
		String[] tokens;
		if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
			tokens = trimmed.substring(1, trimmed.length() - 1).split(",");
		} else {
			tokens = trimmed.split("[,\\s]+");
		}
		List<String> result = new ArrayList<>();
		for (String raw : tokens) {
			String token = raw.replaceAll("[\\[\\]\"']", "").trim();
			if (!token.isEmpty()) {
				result.add(token);
			}
		}
		return result;
	}

	/**
	 * 校验并过滤 token：跳过重复项与无效项，返回可添加的合法类型及统计信息。
	 * <p>
	 * Task 16.2 关联：区分"重复项"与"无效项"分别计数，供屏幕显示不同颜色提示。
	 * <p>
	 * 重复判断使用动态 seen 集合（包含已存在项 + 本次已新增项），
	 * 确保剪贴板内部重复 token 也能被正确识别为重复而非新增。
	 *
	 * @param tokens        待导入的原始 token
	 * @param existingTypes 当前已存在的蜜蜂类型（用于去重判断，不会被修改）
	 * @return 导入结果（含新增列表、重复数、无效数及提示消息）
	 */
	static ImportResult validateImport(List<String> tokens, List<String> existingTypes) {
		Set<String> seen = new HashSet<>(existingTypes);
		List<String> added = new ArrayList<>();
		int duplicates = 0;
		int invalid = 0;
		for (String token : tokens) {
			// 优先判断重复（有效但已存在或本次已新增），避免重复解析 ResourceLocation
			if (seen.contains(token)) {
				duplicates++;
				continue;
			}
			if (isValidBeeType(token)) {
				added.add(token);
				seen.add(token);
			} else {
				invalid++;
			}
		}
		return new ImportResult(added, duplicates, invalid);
	}

	/**
	 * 校验单个 token 是否为合法且存在的蜜蜂类型。
	 */
	private static boolean isValidBeeType(String token) {
		ResourceLocation beeType = BeeInfoHelper.parseBeeType(token);
		return beeType != null && BeeInfoHelper.isBeeTypeExists(beeType);
	}

	// ========== 导入结果 ==========

	/**
	 * 导入结果容器
	 * <p>
	 * 封装新增列表、重复数、无效数，并提供提示消息与颜色的构建方法，
	 * 供屏幕端根据结果展示限时提示（Task 16.2）。
	 */
	static final class ImportResult {

		private final List<String> added;
		private final int duplicates;
		private final int invalid;

		ImportResult(List<String> added, int duplicates, int invalid) {
			this.added = added;
			this.duplicates = duplicates;
			this.invalid = invalid;
		}

		/** 可新增的合法类型列表 */
		List<String> getAdded() {
			return added;
		}

		/** 重复项数量 */
		int getDuplicates() {
			return duplicates;
		}

		/** 无效项数量 */
		int getInvalid() {
			return invalid;
		}

		/** 是否存在新增项 */
		boolean hasAdded() {
			return !added.isEmpty();
		}

		/**
		 * 根据导入结果生成提示消息。
		 *
		 * @return 提示消息；无可见变化（全为空）时返回 {@code null}
		 */
		Component buildMessage() {
			if (invalid > 0 && duplicates > 0) {
				// 混合情况：橙色提示
				return Component.translatable(
						"productivebeesgenesis.config.import.result.mixed",
						added.size(), duplicates, invalid);
			} else if (invalid > 0) {
				// 仅无效项：红色警告
				return Component.translatable(
						"productivebeesgenesis.config.import.result.invalid",
						added.size(), invalid);
			} else if (duplicates > 0) {
				// 仅重复项：黄色提示
				return Component.translatable(
						"productivebeesgenesis.config.import.result.duplicate",
						added.size(), duplicates);
			} else if (!added.isEmpty()) {
				// 全部成功：绿色提示
				return Component.translatable(
						"productivebeesgenesis.config.import.result.success",
						added.size());
			}
			return null;
		}

		/**
		 * 根据导入结果返回提示颜色（ARGB）。
		 */
		int buildColor() {
			if (invalid > 0 && duplicates > 0) {
				return 0xFFFFA500;
			} else if (invalid > 0) {
				return 0xFFFF6060;
			} else if (duplicates > 0) {
				return 0xFFFFC040;
			}
			return 0xFF60FF60;
		}
	}
}
