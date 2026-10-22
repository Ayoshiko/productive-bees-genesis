package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import java.util.List;
import java.util.Locale;

/** 多个词为交集；前缀只改变该词的搜索范围。 */
final class DesignerSearch {
	private DesignerSearch() {
	}

	static boolean matches(String query, String namespace, String searchable, List<String> tags) {
		return compile(query).matches(namespace, searchable, tags);
	}

	static Query compile(String query) {
		return new Query(query.toLowerCase(Locale.ROOT).strip().split("\\s+"));
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
