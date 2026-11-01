package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ibm.icu.text.Transliterator;
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
			if (pinyin == null) {
				pinyin = Transliterator.getInstance("Han-Latin");
			}
			String latin = fold(pinyin.transliterate(runs.group()));
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

	static String fold(String text) {
		return Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
				.replace("u\u0308", "v").replace("u:", "v").replaceAll("\\p{M}+", "");
	}

	static void clear() {
		NAMES.clear();
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
