package com.ayoshiko.productivebeesgenesis.authoring;

import com.google.gson.JsonParser;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** 对外导出名来自内容和时间；不承担内部修订身份。 */
public final class BeeDesignerExportName {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

	private BeeDesignerExportName() {
	}

	public static String validate(String name) {
		if (name.isBlank() || name.length() > 128 || name.endsWith(".") || name.endsWith(" ")
				|| name.equals(".") || name.equals("..") || name.matches("(?s).*[<>:\"/\\\\|?*\\p{Cntrl}].*")
				|| name.split("\\.", 2)[0].stripTrailing().matches("(?i:CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])")) {
			throw new IllegalArgumentException("designer.error.export_name");
		}
		return name;
	}

	public static String suggest(Collection<String> ids, String format) {
		return suggest(ids, format, LocalDateTime.now());
	}

	static String suggest(Collection<String> ids, String format, LocalDateTime time) {
		String content = ids.size() == 1 ? ids.iterator().next().replace(':', '-').replace('/', '-') : ids.size() + "-bees";
		if (content.length() > 48) {
			content = content.substring(0, 48);
		}
		String prefix = format.startsWith("runtime_") ? "pbg-bees-release-" : "pbg-bees-";
		return validate(prefix + content + "-" + format.replace("runtime_", "").toLowerCase(Locale.ROOT) + "-" + TIME.format(time));
	}

	static String fromFiles(Map<String, String> files, String format) {
		String index = files.get("data/productivebeesgenesis/bee_designer/index.json");
		Collection<String> ids;
		if (index != null) {
			ids = JsonParser.parseString(index).getAsJsonObject().keySet();
		} else {
			ids = files.entrySet().stream().filter(entry -> entry.getKey().startsWith("data/")
					&& entry.getKey().contains("/productivebees/") && entry.getKey().endsWith(".json"))
					.filter(entry -> !JsonParser.parseString(entry.getValue()).getAsJsonObject().has(BeeDesignerIdentity.TARGET))
					.map(entry -> {
						String[] parts = entry.getKey().split("/", 4);
						return parts[1] + ":" + parts[3].substring(0, parts[3].length() - 5);
					}).toList();
			if (ids.isEmpty()) {
				ids = List.of("bee-recipes");
			}
		}
		return suggest(ids, format);
	}
}
