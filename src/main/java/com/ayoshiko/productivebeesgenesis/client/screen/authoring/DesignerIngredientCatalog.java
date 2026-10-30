package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import mekanism.api.MekanismAPI;
import mekanism.api.chemical.Chemical;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.core.HolderSet;
import com.google.gson.JsonObject;
import com.mojang.datafixers.util.Pair;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.LinkedHashMap;

/** 客户端展示索引按连接和语言失效；不保存世界或玩家，也不解码配方组件。 */
final class DesignerIngredientCatalog {
	private static final Map<String, Source> SOURCES = new HashMap<>();
	private static WeakReference<ClientLevel> level = new WeakReference<>(null);
	private static Language language;

	private DesignerIngredientCatalog() {
	}

	static Source source(String kind) {
		return source(kind, false);
	}

	static Source source(String kind, boolean nestsFirst) {
		var mc = Minecraft.getInstance();
		if (level.get() != mc.level || language != Language.getInstance()) {
			clear();
			level = new WeakReference<>(mc.level);
			language = Language.getInstance();
		}
		if (nestsFirst) {
			source(kind, false);
		}
		return SOURCES.computeIfAbsent(kind + (nestsFirst ? ":nests" : ":ordinary"), unused -> new Source(kind, nestsFirst));
	}

	static void clear() {
		SOURCES.clear();
		level.clear();
		language = null;
	}

	static final class Source {
		private final String kind;
		private final boolean nestsFirst;
		private final Source base;
		private final Iterator<?> registry;
		private final List<Entry> entries = new ArrayList<>();
		private boolean ready;
		private long indexingNanos;
		private String lastQuery;
		private DesignerSearch.Query compiledQuery;
		private final Map<String, List<Entry>> recentQueries = new LinkedHashMap<>(8, 0.75F, true);
		private int searched;
		private final List<Entry> filtered = new ArrayList<>();
		private List<Entry> active = filtered;

		private Source(String kind, boolean nestsFirst) {
			this.kind = kind;
			this.nestsFirst = nestsFirst;
			base = nestsFirst ? source(kind, false) : null;
			if (base != null) {
				registry = java.util.Collections.emptyIterator();
				return;
			}
			registry = switch (kind) {
				case "item" -> BuiltInRegistries.ITEM.iterator();
				case "block" -> BuiltInRegistries.BLOCK.iterator();
				case "block_tag" -> BuiltInRegistries.BLOCK.getTags().iterator();
				case "item_tag" -> BuiltInRegistries.ITEM.getTags().iterator();
				case "entity_tag" -> BuiltInRegistries.ENTITY_TYPE.getTags().iterator();
				case "fluid" -> BuiltInRegistries.FLUID.iterator();
				case "chemical" -> MekanismAPI.CHEMICAL_REGISTRY.iterator();
				default -> throw new IllegalArgumentException("Unsupported material browser: " + kind);
			};
		}

		boolean advance() {
			if (ready) {
				return false;
			}
			long started = System.nanoTime();
			if (base != null) {
				base.advance();
				if (!base.ready) {
					return true;
				}
				entries.addAll(base.entries);
			}
			int budget = 1024;
			while (registry.hasNext() && budget-- > 0 && System.nanoTime() - started < 2_000_000L) {
				Object value = registry.next();
				Entry entry = entry(value);
				if (entry != null) {
					entries.add(entry);
				}
			}
			if (!registry.hasNext()) {
				entries.sort(Comparator.comparingInt((Entry entry) -> nestsFirst && entry.nest ? 0 : 1).thenComparing(entry -> entry.idText));
				lastQuery = null;
				recentQueries.clear();
				ready = true;
			}
			indexingNanos += System.nanoTime() - started;
			return true;
		}

		private Entry entry(Object value) {
			if (value instanceof Item item) {
				ItemStack stack = item.getDefaultInstance();
				return stack.isEmpty() ? null : new Entry(BuiltInRegistries.ITEM.getKey(item), stack, null, null, stack);
			}
			if (value instanceof Block block) {
				if (block == Blocks.AIR) {
					return null;
				}
				var id = BuiltInRegistries.BLOCK.getKey(block);
				JsonObject selection = new JsonObject();
				selection.addProperty("kind", "block");
				selection.addProperty("id", id.toString());
				return new Entry(id, selection, block.getName(), null, new ItemStack(block));
			}
			if (value instanceof Pair<?, ?> pair && pair.getSecond() instanceof HolderSet.Named<?> holders) {
				var id = holders.key().location();
				JsonObject selection = new JsonObject();
				selection.addProperty("kind", kind);
				selection.addProperty("id", id.toString());
				ItemStack preview = ItemStack.EMPTY;
				if (holders.size() > 0) {
					Object first = holders.get(0).value();
					if (first instanceof Block block) {
						preview = new ItemStack(block);
					} else if (first instanceof Item item) {
						preview = new ItemStack(item);
					} else if (first instanceof EntityType<?> entity) {
						SpawnEggItem egg = SpawnEggItem.byId(entity);
						if (egg != null) preview = new ItemStack(egg);
					}
				}
				return new Entry(id, selection, Component.literal("#" + id + " · " + holders.size()), List.of(id.toString()), preview);
			}
			if (value instanceof Fluid fluid) {
				if (fluid == Fluids.EMPTY || !fluid.isSource(fluid.defaultFluidState())) {
					return null;
				}
				FluidStack stack = new FluidStack(fluid, 1000);
				return new Entry(BuiltInRegistries.FLUID.getKey(fluid), stack, stack.getHoverName());
			}
			Chemical chemical = (Chemical) value;
			ResourceLocation id = MekanismAPI.CHEMICAL_REGISTRY.getKey(chemical);
			return id.toString().equals("mekanism:empty") ? null : new Entry(id,
					new ChemicalStack(MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(chemical), 1000), chemical.getTextComponent());
		}

		List<Entry> search(String query) {
			String normalized = query.toLowerCase(Locale.ROOT).strip();
			if (normalized.isEmpty()) {
				lastQuery = normalized;
				searched = entries.size();
				active = entries;
				return active;
			}
			if (!normalized.equals(lastQuery)) {
				lastQuery = normalized;
				compiledQuery = DesignerSearch.compile(normalized);
				searched = 0;
				filtered.clear();
				active = filtered;
				List<Entry> cached = ready ? recentQueries.get(normalized) : null;
				if (cached != null) {
					active = cached;
					searched = entries.size();
					return active;
				}
			}
			long started = System.nanoTime();
			int budget = 512;
			boolean names = compiledQuery.needsName();
			boolean tags = compiledQuery.needsTags();
			while (searched < entries.size() && budget-- > 0 && System.nanoTime() - started < 2_000_000L) {
				Entry entry = entries.get(searched++);
				if (compiledQuery.matches(entry.id.getNamespace(), names ? entry.searchable() : entry.idText,
						tags ? entry.tags() : List.of())) {
					filtered.add(entry);
				}
			}
			if (ready && searched == entries.size() && !recentQueries.containsKey(normalized)) {
				if (recentQueries.size() == 8) {
					recentQueries.remove(recentQueries.keySet().iterator().next());
				}
				recentQueries.put(normalized, List.copyOf(filtered));
			}
			return active;
		}

		boolean searching() {
			return lastQuery == null || searched < entries.size();
		}

		boolean ready() {
			return ready;
		}

		long indexingNanos() {
			return indexingNanos;
		}

		int size() {
			return entries.size();
		}
	}

	static final class Entry {
		final ResourceLocation id;
		final String idText;
		final Object ingredient;
		Component name;
		private String searchable;
		private List<String> tags;
		final boolean nest;
		private final ItemStack preview;

		Entry(ResourceLocation id, Object ingredient, Component name) {
			this(id, ingredient, name, List.of(), ItemStack.EMPTY);
		}

		Entry(ResourceLocation id, Object ingredient, Component name, List<String> tags, ItemStack preview) {
			this.id = id;
			idText = id.toString();
			this.ingredient = ingredient;
			this.name = name;
			this.tags = tags;
			this.preview = preview;
			var block = BuiltInRegistries.BLOCK.get(id);
			nest = id.getNamespace().equals("productivebees") && (block instanceof cy.jdkdigital.productivebees.common.block.AdvancedBeehiveAbstract
					|| block instanceof net.minecraft.world.level.block.BeehiveBlock);
		}

		String searchable() {
			if (searchable == null) {
				if (name == null) {
					name = ((ItemStack) ingredient).getHoverName();
				}
				searchable = DesignerSearch.indexed(idText + " " + name.getString());
			}
			return searchable;
		}

		List<String> tags() {
			if (tags == null) {
				if (ingredient instanceof JsonObject object && "block".equals(object.get("kind").getAsString())) {
					tags = BuiltInRegistries.BLOCK.wrapAsHolder(BuiltInRegistries.BLOCK.get(id)).tags()
							.map(tag -> tag.location().toString()).toList();
				} else {
					var item = ((ItemStack) ingredient).getItem();
					tags = BuiltInRegistries.ITEM.wrapAsHolder(item).tags().map(tag -> tag.location().toString()).toList();
				}
			}
			return tags;
		}

		Object copy() {
			if (ingredient instanceof JsonObject object) {
				return object.deepCopy();
			}
			if (ingredient instanceof ItemStack stack) {
				return stack.copyWithCount(1);
			}
			if (ingredient instanceof FluidStack stack) {
				return stack.copy();
			}
			return ((ChemicalStack) ingredient).copy();
		}

		private String kindLabel() {
			JsonObject object = (JsonObject) ingredient;
			return object.get("kind").getAsString().endsWith("tag") ? "#" : "B";
		}

		void draw(GuiGraphics graphics, int x, int y) {
			if (ingredient instanceof JsonObject) {
				if (preview.isEmpty()) {
					graphics.renderOutline(x + 2, y + 2, 12, 12, DesignerFormScreen.MUTED);
					graphics.drawString(Minecraft.getInstance().font, kindLabel(), x + 5, y + 4, DesignerFormScreen.ACCENT, false);
				} else {
					graphics.renderItem(preview, x, y);
				}
			} else if (ingredient instanceof ItemStack stack) {
				graphics.renderItem(stack, x, y);
			} else if (ingredient instanceof FluidStack stack) {
				DesignerResourceButton.drawFluid(graphics, stack, x, y);
			} else {
				graphics.pose().pushPose();
				graphics.pose().translate(x, y, 0);
				com.ayoshiko.productivebeesgenesis.client.resource.ResourceVisuals.draw(graphics,
						com.ayoshiko.productivebeesgenesis.resource.ResourceKey.chemical(id));
				graphics.pose().popPose();
			}
		}
	}
}
