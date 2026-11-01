package com.ayoshiko.productivebeesgenesis.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerScriptTokens.Token;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.mojang.serialization.JsonOps;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** 只解析数据表达式和有限调用链；不载入 Rhino、不求值或执行脚本。 */
public final class BeeDesignerMachineScripts {
	private static final Pattern COUNT = Pattern.compile("^([0-9]+)x\\s+(.+)$", Pattern.DOTALL);
	private final List<Token> tokens;
	private final ArrayDeque<Map<String, JsonElement>> scopes = new ArrayDeque<>();
	private final ArrayDeque<Boolean> dynamicScopes = new ArrayDeque<>();
	private final Map<String, Macro> macros = new HashMap<>();

	public record Candidate(String id, JsonObject recipe, String error, int line, int start, int end) {
		public boolean parsed() {
			return error.isEmpty();
		}
	}

	private record Macro(List<String> parameters, int body) {
	}

	private record Chain(String host, String type, int arguments, int end) {
	}

	private BeeDesignerMachineScripts(String source) {
		tokens = BeeDesignerScriptTokens.read(source);
		scopes.push(new HashMap<>());
		dynamicScopes.push(false);
	}

	public static List<Candidate> parse(String source) {
		if (source.getBytes(StandardCharsets.UTF_8).length > BeeDesignerStore.MAX_PACK_BYTES) {
			throw new IllegalArgumentException("Script exceeds the source import budget");
		}
		return new BeeDesignerMachineScripts(source).parse();
	}

	private List<Candidate> parse() {
		List<Candidate> result = new ArrayList<>();
		boolean pendingControl = false;
		for (int index = 0; index < tokens.size(); index++) {
			Token token = tokens.get(index);
			if (token.string() == null && List.of("if", "for", "while", "switch", "function", "try", "catch", "class", "with").contains(token.text())) {
				pendingControl = true;
			}
			if (token.is("{")) {
				scopes.push(new HashMap<>(scopes.peek()));
				boolean recipeCallback = index >= 6 && tokens.get(index - 6).is("ServerEvents")
						&& tokens.get(index - 4).is("recipes") && tokens.get(index - 1).is("=>");
				dynamicScopes.push(dynamicScopes.peek() || pendingControl || !recipeCallback);
				pendingControl = false;
			} else if (token.is("}") && scopes.size() > 1) {
				scopes.pop();
				dynamicScopes.pop();
				pendingControl = false;
			} else if (token.is(";")) {
				pendingControl = false;
			} else if (token.is("const") && index + 3 < tokens.size() && tokens.get(index + 2).is("=")) {
				int declaration = dynamicScopes.peek() || pendingControl ? index : constant(index);
				if (declaration > index) {
					index = declaration - 1;
				}
			} else if (chain(index) != null) {
				Chain chain = chain(index);
				if (dynamicScopes.peek() || pendingControl) {
					result.add(new Candidate("", null, "Conditional or function body preserved without evaluation", token.line(),
							token.start(), tokens.get(chain.end() - 1).end()));
				} else {
					result.add(candidate(chain, scopes.peek(), token.start(), tokens.get(chain.end() - 1).end(), token.line()));
				}
				index = chain.end() - 1;
			} else if (macros.containsKey(token.text()) && index + 1 < tokens.size() && tokens.get(index + 1).is("(")) {
				Macro macro = macros.get(token.text());
				int end = close(index + 1) + 1;
				try {
					Cursor cursor = new Cursor(index + 2, scopes.peek());
					List<JsonElement> values = cursor.arguments();
					if (values.size() != macro.parameters().size()) {
						throw new IllegalArgumentException("Helper argument count differs from its literal declaration");
					}
					Map<String, JsonElement> bindings = new HashMap<>(scopes.peek());
					for (int position = 0; position < values.size(); position++) {
						bindings.put(macro.parameters().get(position), values.get(position));
					}
					if (dynamicScopes.peek() || pendingControl) {
						throw new IllegalArgumentException("Conditional helper invocation preserved without evaluation");
					}
					result.add(candidate(chain(macro.body()), bindings, token.start(), tokens.get(end - 1).end(), token.line()));
				} catch (IllegalArgumentException failure) {
					result.add(new Candidate("", null, failure.getMessage(), token.line(), token.start(), tokens.get(end - 1).end()));
				}
				index = end - 1;
			}
			if (result.size() > 2048) {
				throw new IllegalArgumentException("Script exceeds 2048 recipe declarations");
			}
		}
		return List.copyOf(result);
	}

	private int constant(int index) {
		String name = tokens.get(index + 1).text();
		int value = index + 3;
		if (tokens.get(value).is("(")) {
			int end = close(value);
			if (end + 2 < tokens.size() && tokens.get(end + 1).is("=>") && chain(end + 2) != null) {
				List<String> parameters = new ArrayList<>();
				for (int parameter = value + 1; parameter < end; parameter += 2) {
					if (!tokens.get(parameter).text().matches("[A-Za-z_$][A-Za-z0-9_$]*")
							|| parameter + 1 < end && !tokens.get(parameter + 1).is(",")) {
						return index;
					}
					parameters.add(tokens.get(parameter).text());
				}
				macros.put(name, new Macro(List.copyOf(parameters), end + 2));
				return chain(end + 2).end();
			}
		}
		try {
			Cursor cursor = new Cursor(value, scopes.peek());
			JsonElement literal = cursor.value(0);
			if (cursor.position == tokens.size() || tokens.get(cursor.position).is(";")
					|| tokens.get(cursor.position).line() > tokens.get(cursor.position - 1).line()) {
				scopes.peek().put(name, literal);
				return cursor.position;
			}
		} catch (IllegalArgumentException failure) {
			// 不支持的声明没有进入绑定表；其配方引用会作为未解析候选保管。
		}
		return index;
	}

	private Chain chain(int index) {
		if (index + 7 < tokens.size() && tokens.get(index).is("event") && tokens.get(index + 1).is(".")
				&& tokens.get(index + 2).is("recipes") && tokens.get(index + 3).is(".")
				&& tokens.get(index + 5).is(".") && tokens.get(index + 7).is("(")) {
			String host = tokens.get(index + 4).text();
			if (List.of("modern_industrialization", "mbd2", "modular_machinery_reborn").contains(host)) {
				return new Chain(host, tokens.get(index + 6).text(), index + 8, chainEnd(index + 7));
			}
		}
		if (index + 3 < tokens.size() && tokens.get(index).is("event") && tokens.get(index + 1).is(".")
				&& tokens.get(index + 2).is("custom") && tokens.get(index + 3).is("(")) {
			return new Chain("custom", "", index + 4, chainEnd(index + 3));
		}
		return null;
	}

	private int chainEnd(int opening) {
		int end = close(opening) + 1;
		while (end + 2 < tokens.size() && tokens.get(end).is(".") && tokens.get(end + 2).is("(")) {
			end = close(end + 2) + 1;
		}
		return end;
	}

	private int close(int opening) {
		int depth = 0;
		for (int index = opening; index < tokens.size(); index++) {
			Token token = tokens.get(index);
			if (token.is("(")) {
				depth++;
			} else if (token.is(")") && --depth == 0) {
				return index;
			}
		}
		throw new IllegalArgumentException("Unclosed call at line " + tokens.get(opening).line());
	}

	private Candidate candidate(Chain chain, Map<String, JsonElement> bindings, int start, int end, int line) {
		try {
			Cursor cursor = new Cursor(chain.arguments(), bindings);
			List<JsonElement> arguments = cursor.arguments();
			JsonObject recipe;
			if (chain.host().equals("custom")) {
				require(arguments, 1);
				recipe = arguments.get(0).getAsJsonObject().deepCopy();
				if (BeeDesignerMachineRecipes.host(recipe) == null) {
					throw new IllegalArgumentException("Not a supported machine recipe");
				}
			} else {
				recipe = BeeDesignerDocument.recipe(chain.host() + ":" + chain.type());
				if (chain.host().equals("modern_industrialization")) {
					require(arguments, 2);
					recipe.add("eu", arguments.get(0));
					recipe.add("duration", arguments.get(1));
				} else if (chain.host().equals("mbd2")) {
					require(arguments, 0);
					recipe.addProperty("duration", 20);
					recipe.addProperty("priority", 0);
				} else {
					require(arguments, 2);
					recipe.add("machine", arguments.get(0));
					recipe.add("time", arguments.get(1));
					recipe.add("requirements", new JsonArray());
				}
			}
			String id = "";
			while (cursor.position < chain.end()) {
				cursor.expect(".");
				String method = tokens.get(cursor.position++).text();
				cursor.expect("(");
				List<JsonElement> values = cursor.arguments();
				if (method.equals("id")) {
					require(values, 1);
					id = new ResourceLocation(values.get(0).getAsString()).toString();
				} else {
					apply(recipe, method, values);
				}
			}
			if (id.isEmpty() && BeeDesignerMachineRecipes.host(recipe) == BeeDesignerMachineRecipes.Host.MBD2) {
				id = BeeDesignerDocument.text(recipe, "id", "");
			}
			if (id.isEmpty()) {
				id = "bee_designer:imported/" + hash(recipe.toString()).substring(0, 24);
			}
			BeeDesignerMachineRecipes.recipeIdentity(recipe, id);
			return new Candidate(id, recipe, "", line, start, end);
		} catch (RuntimeException failure) {
			String message = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
			return new Candidate("", null, message, line, start, end);
		}
	}

	private static void apply(JsonObject recipe, String method, List<JsonElement> args) {
		var host = BeeDesignerMachineRecipes.host(recipe);
		if (host == BeeDesignerMachineRecipes.Host.MI && List.of("itemIn", "itemOut", "fluidIn", "fluidOut").contains(method)) {
			require(args, 1);
			String kind = method.startsWith("item") ? "item" : "fluid";
			BeeDesignerMachineRecipes.append(recipe, kind, method.endsWith("In"), material(args.get(0).getAsString(), kind));
		} else if (host == BeeDesignerMachineRecipes.Host.MBD2 && List.of("inputItems", "outputItems", "inputFluids", "outputFluids").contains(method)) {
			String kind = method.endsWith("Items") ? "item" : "fluid";
			for (JsonElement arg : args) {
				BeeDesignerMachineRecipes.append(recipe, kind, method.startsWith("input"), material(arg.getAsString(), kind));
			}
		} else if (host == BeeDesignerMachineRecipes.Host.MBD2 && List.of("inputFE", "outputFE").contains(method)) {
			require(args, 1);
			JsonObject energy = new JsonObject();
			energy.add("amount", args.get(0));
			BeeDesignerMachineRecipes.append(recipe, "energy", method.equals("inputFE"), energy);
		} else if (host == BeeDesignerMachineRecipes.Host.MMR && List.of("requireItem", "produceItem", "requireEnergy").contains(method)) {
			if (args.size() != 3 && !(args.size() == 4 && !method.equals("requireEnergy"))) {
				throw new IllegalArgumentException("Unsupported MMR arguments: " + method);
			}
			String kind = method.equals("requireEnergy") ? "energy" : "item";
			JsonObject value = new JsonObject();
			if (kind.equals("energy")) {
				value.add("amount", args.get(0));
			} else {
				value = material(args.get(0).getAsString(), kind);
			}
			BeeDesignerMachineRecipes.append(recipe, kind, !method.equals("produceItem"), value);
			var entries = BeeDesignerMachineRecipes.entries(recipe);
			JsonObject node = entries.get(entries.size() - 1).node();
				JsonObject position = new JsonObject();
				position.add("x", args.get(args.size() - 2).deepCopy());
				position.add("y", args.get(args.size() - 1).deepCopy());
				node.add("position", position);
			if (args.size() == 4) {
				node.add("chance", args.get(1));
			}
		} else if (List.of("duration", "width", "height", "priority", "progressData").contains(method)) {
			require(args, 1);
			recipe.add(method, args.get(0).deepCopy());
		} else {
			throw new IllegalArgumentException("Unsupported call preserved in original source: " + method);
		}
	}

	public static JsonObject material(String text, String kind) {
		String value = text.strip();
		int count = 1;
		var counted = COUNT.matcher(value);
		if (counted.matches()) {
			count = new java.math.BigDecimal(counted.group(1)).intValueExact();
			value = counted.group(2).strip();
		}
		if (kind.equals("fluid")) {
			int space = value.lastIndexOf(' ');
			if (space >= 0) {
				count = new java.math.BigDecimal(value.substring(space + 1)).intValueExact();
				value = value.substring(0, space).strip();
			}
			JsonObject fluid = new JsonObject();
			boolean tag = value.startsWith("#");
			fluid.addProperty(tag ? "tag" : "fluid", new ResourceLocation(tag ? value.substring(1) : value).toString());
			fluid.addProperty("amount", count);
			return fluid;
		}
		JsonObject stack = new JsonObject();
		int bracket = value.indexOf('[');
		String item = bracket < 0 ? value : value.substring(0, bracket);
		if (item.startsWith("#")) {
			if (bracket >= 0) {
				throw new IllegalArgumentException("Tag components require an explicit ingredient");
			}
			stack.addProperty("tag", new ResourceLocation(item.substring(1)).toString());
			stack.addProperty("count", count);
			return stack;
		}
		stack.addProperty("id", new ResourceLocation(item).toString());
		stack.addProperty("count", count);
		if (bracket >= 0) {
			if (!value.endsWith("]")) {
				throw new IllegalArgumentException("Unclosed item component list");
			}
			JsonObject components = new JsonObject();
			for (String part : splitComponents(value.substring(bracket + 1, value.length() - 1))) {
				int separator = part.indexOf('=');
				if (separator < 1) {
					throw new IllegalArgumentException("Unsupported item component: " + part);
				}
				String name = new ResourceLocation(part.substring(0, separator).strip()).toString();
				try {
					CompoundTag data = TagParser.parseTag("{value:" + part.substring(separator + 1) + "}");
					JsonObject json = CompoundTag.CODEC.encodeStart(JsonOps.INSTANCE, data).getOrThrow(false, message -> {}).getAsJsonObject();
					if (components.has(name)) {
						throw new IllegalArgumentException("Duplicate component: " + name);
					}
					components.add(name, json.get("value"));
				} catch (com.mojang.brigadier.exceptions.CommandSyntaxException failure) {
					throw new IllegalArgumentException("Invalid component " + name, failure);
				}
			}
			stack.add("components", components);
		}
		if (count < 1) {
			throw new IllegalArgumentException("Item count must be positive");
		}
		return BeeDesignerMachineRecipes.itemMaterial(stack);
	}

	private static List<String> splitComponents(String text) {
		List<String> result = new ArrayList<>();
		int start = 0;
		int depth = 0;
		char quote = 0;
		boolean escaped = false;
		for (int index = 0; index < text.length(); index++) {
			char value = text.charAt(index);
			if (quote != 0) {
				if (escaped) {
					escaped = false;
				} else if (value == '\\') {
					escaped = true;
				} else if (value == quote) {
					quote = 0;
				}
			} else if (value == '\'' || value == '"') {
				quote = value;
			} else if (value == '{' || value == '[') {
				if (++depth > 24) {
					throw new IllegalArgumentException("Item component nesting exceeds 24 levels");
				}
			} else if (value == '}' || value == ']') {
				depth--;
			} else if (value == ',' && depth == 0) {
				result.add(text.substring(start, index));
				start = index + 1;
			}
		}
		if (quote != 0 || depth != 0) {
			throw new IllegalArgumentException("Unbalanced component expression");
		}
		result.add(text.substring(start));
		return result;
	}

	private final class Cursor {
		private int position;
		private final Map<String, JsonElement> bindings;

		private Cursor(int position, Map<String, JsonElement> bindings) {
			this.position = position;
			this.bindings = bindings;
		}

		private void expect(String text) {
			if (position >= tokens.size() || !tokens.get(position).is(text)) {
				throw new IllegalArgumentException("Expected " + text + " in literal recipe call");
			}
			position++;
		}

		private List<JsonElement> arguments() {
			List<JsonElement> values = new ArrayList<>();
			while (position < tokens.size() && !tokens.get(position).is(")")) {
				values.add(value(0));
				if (values.size() > 64) {
					throw new IllegalArgumentException("Too many literal arguments");
				}
				if (!tokens.get(position).is(")")) {
					expect(",");
				}
			}
			expect(")");
			return values;
		}

		private JsonElement value(int depth) {
			if (depth > 24 || position >= tokens.size()) {
				throw new IllegalArgumentException("Invalid or excessive literal nesting");
			}
			Token token = tokens.get(position++);
			if (token.string() != null) {
				if (token.text().startsWith("`") || token.text().startsWith("/")) {
					throw new IllegalArgumentException("Template or regular expression preserved without evaluation");
				}
				return new JsonPrimitive(token.string());
			}
			if (token.is("{")) {
				JsonObject object = new JsonObject();
				while (!tokens.get(position).is("}")) {
					Token key = tokens.get(position++);
					expect(":");
					String name = key.string() == null ? key.text() : key.string();
					if (object.has(name)) {
						throw new IllegalArgumentException("Duplicate literal property: " + name);
					}
					object.add(name, value(depth + 1));
					if (!tokens.get(position).is("}")) {
						expect(",");
					}
				}
				expect("}");
				return object;
			}
			if (token.is("[")) {
				JsonArray array = new JsonArray();
				while (!tokens.get(position).is("]")) {
					array.add(value(depth + 1));
					if (!tokens.get(position).is("]")) {
						expect(",");
					}
				}
				expect("]");
				return array;
			}
			if (token.is("ProgressData")) {
				expect(".");
				expect("create");
				expect("(");
				expect(")");
				JsonObject data = new JsonObject();
				JsonObject point = BeeDesignerMachineRecipes.position(74, 8);
				while (position < tokens.size() && tokens.get(position).is(".")) {
					position++;
					String axis = tokens.get(position++).text();
					if (!axis.equals("x") && !axis.equals("y")) {
						throw new IllegalArgumentException("Unsupported progress data call: " + axis);
					}
					expect("(");
					point.add(axis, value(depth + 1));
					expect(")");
				}
				data.add("position", point);
				return data;
			}
			if (token.is("true") || token.is("false")) {
				return new JsonPrimitive(token.is("true"));
			}
			if (token.is("null")) {
				return com.google.gson.JsonNull.INSTANCE;
			}
			if (bindings.containsKey(token.text())) {
				return bindings.get(token.text()).deepCopy();
			}
			try {
				return new JsonPrimitive(new java.math.BigDecimal(token.text()));
			} catch (NumberFormatException failure) {
				throw new IllegalArgumentException("Unresolved expression preserved: " + token.text());
			}
		}
	}

	private static void require(List<JsonElement> arguments, int count) {
		if (arguments.size() != count) {
			throw new IllegalArgumentException("Expected " + count + " literal arguments");
		}
	}

	public static String hash(String text) {
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}
	}

	public static String export(JsonObject document) {
		StringBuilder script = new StringBuilder("// Bee Designer: install separately in kubejs/server_scripts.\nServerEvents.recipes(event => {\n");
		for (var entry : document.getAsJsonObject("recipes").entrySet()) {
			JsonObject recipe = entry.getValue().getAsJsonObject();
			if (BeeDesignerMachineRecipes.host(recipe) != null) {
				JsonObject output = recipe.deepCopy();
				BeeDesignerMachineRecipes.recipeIdentity(output, entry.getKey());
				script.append("  event.custom(").append(output).append(").id(").append(new JsonPrimitive(entry.getKey())).append(");\n");
			}
		}
		return script.append("});\n").toString();
	}
}
