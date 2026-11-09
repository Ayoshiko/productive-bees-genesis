package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ibm.icu.text.Transliterator;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.text.Normalizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 多个词为交集；前缀只改变该词的搜索范围。 */
final class DesignerSearch {
	private static final Map<String, String> NAMES = new LinkedHashMap<>(256, 0.75F, true);
	private static final int NAME_CACHE_LIMIT = 8192;
	private static final java.util.regex.Pattern HAN_RUN = java.util.regex.Pattern.compile("\\p{IsHan}+");
	private static Transliterator pinyin;
	private static final Int2ObjectOpenHashMap<String> SYLLABLES = new Int2ObjectOpenHashMap<>();
	private static final Set<Integer> CONTEXT_HAN = new HashSet<>();
	private static boolean contextualRules;

	private DesignerSearch() {
	}

	static boolean matches(String query, String namespace, String searchable, List<String> tags) {
		return compile(query).matches(namespace, searchable, tags);
	}

	static Query compile(String query) {
		return new Query(fold(query).strip().split("\\s+"));
	}

	/** 仅客户端调用；缓存只持有名称文本，改名自然换键，重载和登出清理。 */
	static String indexed(String text) {
		String cached = NAMES.get(text);
		if (cached != null) {
			return cached;
		}
		StringBuilder result = new StringBuilder(fold(text));
		var runs = HAN_RUN.matcher(text);
		while (runs.find()) {
			String latin = latin(runs.group());
			StringBuilder joined = new StringBuilder();
			StringBuilder initials = new StringBuilder();
			for (String syllable : latin.split("[^a-z0-9v]+")) {
				if (!syllable.isEmpty()) {
					joined.append(syllable);
					initials.append(syllable.charAt(0));
				}
			}
			result.append(' ').append(latin).append(' ').append(joined).append(' ').append(initials);
			result.append(' ').append(joined.toString().replace('v', 'u'));
		}
		if (NAMES.size() >= NAME_CACHE_LIMIT) {
			NAMES.remove(NAMES.keySet().iterator().next());
		}
		String value = result.toString();
		NAMES.put(text, value);
		return value;
	}


	private static String latin(String han) {
		if (pinyin == null) {
			pinyin = Transliterator.getInstance("Han-Latin");
			// 规则中的上下文汉字仍按整段转换，保留重庆、藏文等读音。
			String rules = pinyin.toRules(false);
			var contexts = Pattern.compile("\\{(.)}").matcher(rules);
			while (contexts.find()) {
				CONTEXT_HAN.add(contexts.group(1).codePointAt(0));
			}
			contextualRules = rules.indexOf('{') >= 0 && CONTEXT_HAN.isEmpty();
		}
		if (contextualRules || han.codePoints().anyMatch(CONTEXT_HAN::contains)) {
			return fold(pinyin.transliterate(han));
		}
		StringBuilder result = new StringBuilder();
		han.codePoints().forEach(character -> {
			String syllable = SYLLABLES.get(character);
			if (syllable == null) {
				syllable = fold(pinyin.transliterate(new String(Character.toChars(character))));
				SYLLABLES.put(character, syllable);
			}
			if (!result.isEmpty()) {
				result.append(' ');
			}
			result.append(syllable);
		});
		return result.toString();
	}

	static String fold(String text) {
		return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
				.replace("u\u0308", "v").replace("u:", "v").replaceAll("\\p{M}+", "");
	}

	static void clear() {
		NAMES.clear();
		SYLLABLES.clear();
	}


	/** 索引只保管封闭的名称文本；一、二字符倒排表缩小候选，最终匹配保留完整查询语义。 */
	static final class Index<T> {
		private final List<Indexed<T>> entries = new ArrayList<>();
		private final List<T> all = new ArrayList<>();
		private final Map<String, IntArrayList> grams = new HashMap<>();
		private final Map<String, List<T>> recent = new LinkedHashMap<>(8, 0.75F, true);
		private final Comparator<T> order;

		Index(Comparator<T> order) {
			this.order = order;
		}

		void add(T value, String namespace, String searchable, List<String> tags) {
			int index = entries.size();
			entries.add(new Indexed<>(value, namespace, searchable, List.copyOf(tags)));
			all.add(value);
			indexText('n', namespace, index);
			indexText('s', searchable, index);
			for (String tag : tags) {
				indexText('t', tag, index);
			}
			recent.clear();
		}

		void finish() {
			all.sort(order);
			recent.clear();
		}

		List<T> search(String query) {
			Query compiled = compile(query);
			String normalized = String.join(" ", compiled.terms());
			if (normalized.isBlank()) {
				return all;
			}
			List<T> cached = recent.get(normalized);
			if (cached != null) {
				return cached;
			}
			IntArrayList candidates = null;
			for (String term : compiled.terms()) {
				char scope = 's';
				String text = term;
				if (term.startsWith("@") || term.startsWith("#")) {
					scope = term.startsWith("@") ? 'n' : 't';
					text = term.substring(1);
				}
				if (text.isEmpty()) {
					continue;
				}
				int width = Math.min(2, text.length());
				for (int offset = 0; offset + width <= text.length(); offset++) {
					var posting = grams.get(scope + text.substring(offset, offset + width));
					if (posting == null) {
						return remember(normalized, List.of());
					}
					if (candidates == null || posting.size() < candidates.size()) {
						candidates = posting;
					}
				}
			}
			List<T> result = new ArrayList<>();
			int count = candidates == null ? entries.size() : candidates.size();
			for (int position = 0; position < count; position++) {
				int index = candidates == null ? position : candidates.getInt(position);
				Indexed<T> entry = entries.get(index);
				if (compiled.matches(entry.namespace(), entry.searchable(), entry.tags())) {
					result.add(entry.value());
				}
			}
			result.sort(order);
			return remember(normalized, List.copyOf(result));
		}

		private List<T> remember(String query, List<T> result) {
			if (recent.size() == 8) {
				recent.remove(recent.keySet().iterator().next());
			}
			recent.put(query, result);
			return result;
		}

		private void indexText(char scope, String text, int index) {
			for (int offset = 0; offset < text.length(); offset++) {
				put(scope + text.substring(offset, offset + 1), index);
				if (offset + 1 < text.length()) {
					put(scope + text.substring(offset, offset + 2), index);
				}
			}
		}

		private void put(String gram, int index) {
			var posting = grams.computeIfAbsent(gram, unused -> new IntArrayList());
			if (posting.isEmpty() || posting.getInt(posting.size() - 1) != index) {
				posting.add(index);
			}
		}

		private record Indexed<T>(T value, String namespace, String searchable, List<String> tags) {}
	}

	record Query(String[] terms) {
		boolean needsName() {
			for (String term : terms) {
				if (!term.isEmpty() && !term.startsWith("@") && !term.startsWith("#")) {
					return true;
				}
			}
			return false;
		}

		boolean needsTags() {
			for (String term : terms) {
				if (term.startsWith("#")) {
					return true;
				}
			}
			return false;
		}
		boolean matches(String namespace, String searchable, List<String> tags) {
			for (String term : terms) {
				if (term.startsWith("@")) {
					if (!namespace.contains(term.substring(1))) {
						return false;
					}
				} else if (term.startsWith("#")) {
					String tag = term.substring(1);
					boolean found = false;
					for (String value : tags) {
						if (value.contains(tag)) {
							found = true;
							break;
						}
					}
					if (!found) {
						return false;
					}
				} else if (!searchable.contains(term)) {
					return false;
				}
			}
			return true;
		}
	}
}
