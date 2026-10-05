package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.*;

/** 有界 AND／排除查询；多个蜂种、基因或花源条件必须由同一个蜂位满足。 */
public final class TerminalFilter {
	public interface Names {
		String text(String kind, String id);
		default boolean matches(String kind, String id, String term) { return text(kind, id).contains(term); }
		default boolean tagged(ProductKey key, String tag) { return false; }
	}
	private record Term(String field, String value, boolean negative) { }
	private static final Set<String> FIELDS = Set.of("name", "id", "mod", "tag", "kind", "bee", "machine", "pos", "dim", "feed", "state", "gene",
			"productivity", "endurance", "temper", "behavior", "weather_tolerance");
	private final List<Term> terms;
	public TerminalFilter(String query) {
		if (query == null || query.length() > 64 || query.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("Invalid filter");
		var parsed = new ArrayList<Term>();
		var matcher = java.util.regex.Pattern.compile("(?:[^\\s\"]+|\"[^\"]*\"?)+").matcher(query.toLowerCase(Locale.ROOT));
		while (matcher.find()) {
			String value = matcher.group(); boolean negative = value.startsWith("-"); if (negative) value = value.substring(1);
			String field = "";
			if (value.startsWith("@") || value.startsWith("#")) { field = value.startsWith("@") ? "mod" : "tag"; value = value.substring(1); }
			else { int colon = value.indexOf(':'); if (colon > 0 && FIELDS.contains(value.substring(0, colon))) { field = value.substring(0, colon); value = value.substring(colon + 1); } }
			value = value.replace("\"", "");
			if (!value.isBlank()) parsed.add(new Term(field, value, negative));
		}
		terms = List.copyOf(parsed);
	}
	public List<String> nameTerms() { return terms.stream().filter(t -> t.field().isEmpty() || t.field().equals("name") || t.field().equals("feed")).map(Term::value).distinct().toList(); }
	public boolean dynamic() { return terms.stream().anyMatch(t -> t.field().equals("state") || t.field().equals("feed") || t.field().equals("tag")); }
	public boolean matches(Row row, Names names) {
		if (terms.isEmpty()) return true;
		if (row instanceof ProductRow product) return terms.stream().allMatch(t -> t.negative() != product(t, product.key(), names));
		var member = (MemberRow) row;
		if (member.bees().isEmpty()) return terms.stream().allMatch(t -> t.negative() != member(t, member, null, names));
		return member.bees().stream().anyMatch(bee -> terms.stream().allMatch(t -> t.negative() != member(t, member, bee, names)));
	}
	private static boolean product(Term term, ProductKey key, Names names) {
		String id = key.id().toString(), value = term.value();
		return switch (term.field()) {
			case "" -> id.contains(value) || names.matches(key.kind().name().toLowerCase(Locale.ROOT), id, value) || customName(key).contains(value);
			case "name" -> names.matches(key.kind().name().toLowerCase(Locale.ROOT), id, value) || customName(key).contains(value);
			case "id" -> id.contains(value);
			case "mod" -> key.id().getNamespace().contains(value);
			case "tag" -> names.tagged(key, value);
			case "kind" -> key.kind().name().equalsIgnoreCase(value);
			default -> false;
		};
	}
	private static String customName(ProductKey key) { return key.component("minecraft:custom_name").map(tag -> tag.getAsString().toLowerCase(Locale.ROOT)).orElse(""); }
	private static boolean member(Term t, MemberRow row, BeeRow bee, Names names) {
		var claim = row.claim(); var pos = claim.origin(); String value = t.value();
		String machine = claim.machine(), beeId = bee == null ? "" : bee.type();
		return switch (t.field()) {
			case "" -> machine.contains(value) || names.matches("machine", machine, value)
					|| (pos.x() + "," + pos.y() + "," + pos.z()).contains(value) || beeId.contains(value) || names.matches("bee", beeId, value);
			case "name" -> names.matches("machine", machine, value) || names.matches("bee", beeId, value);
			case "id" -> machine.contains(value) || beeId.contains(value);
			case "mod" -> machine.substring(0, machine.indexOf(':')).contains(value) || !beeId.isEmpty() && beeId.substring(0, beeId.indexOf(':')).contains(value);
			case "machine" -> machine.contains(value) || names.matches("machine", machine, value);
			case "pos" -> (pos.x() + "," + pos.y() + "," + pos.z()).contains(value);
			case "dim" -> pos.dimension().contains(value);
			case "bee" -> !beeId.isEmpty() && (beeId.contains(value) || names.matches("bee", beeId, value));
			case "feed" -> bee != null && !bee.feedingItem().isEmpty() && (bee.feedingItem().contains(value) || names.matches("item", bee.feedingItem(), value));
			case "state" -> state(bee, value);
			case "gene" -> gene(bee, "", value, names);
			case "productivity", "endurance", "temper", "behavior", "weather_tolerance" -> gene(bee, t.field(), value, names);
			default -> false;
		};
	}
	private static boolean state(BeeRow bee, String value) {
		return switch (value) {
			case "empty" -> bee == null || bee.id() == null;
			case "occupied" -> bee != null && bee.id() != null;
			case "pending" -> bee != null && bee.pending();
			case "enabled" -> bee != null && bee.id() != null && bee.enabled();
			case "disabled" -> bee != null && bee.id() != null && !bee.enabled();
			case "feeding_disabled" -> bee != null && bee.feedingDisabled();
			case "feeding_active" -> bee != null && bee.feedingCount() > 0 && !bee.feedingDisabled();
			default -> false;
		};
	}
	private static boolean gene(BeeRow bee, String field, String value, Names names) {
		if (bee == null || bee.id() == null || bee.genes() == null) return false;
		for (int i = 0; i < TerminalBeeGenes.FIELDS.size(); i++) {
			String name = TerminalBeeGenes.FIELDS.get(i); if (!field.isEmpty() && !field.equals(name)) continue;
			String raw = bee.genes().values().get(i); if (raw.isEmpty()) continue;
			String normalized = raw.startsWith(name + ".") ? raw : name + "." + raw;
			if (normalized.contains(value) || names.text("gene", normalized).contains(value)) return true;
		}
		return false;
	}
}
