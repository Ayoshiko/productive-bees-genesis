package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ResourceKeyBridge;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;

/** 仅由 AE2 守卫入口加载；目录收集真实键，未知第三方数据不由界面猜造。 */
final class DesignerAeKeyPicker extends DesignerFormScreen {
	private static Source cached;
	private final Screen parent;
	private final Consumer<JsonObject> chosen;
	private Source source;
	private String query = "";
	private String type = "all_key_types";
	private long searchAt;
	private int observedSize;
	private boolean observedReady;
	private List<Entry> matches = List.of();
	private String filteredQuery;
	private String filteredType;
	private int itemPage;
	private net.minecraft.client.gui.components.EditBox pageInput;

	DesignerAeKeyPicker(Screen parent, Consumer<JsonObject> chosen) {
		super(tr("pick_ae_key"));
		this.parent = parent;
		editorParent = parent;
		this.chosen = chosen;
	}

	static void clear() {
		cached = null;
	}

	@Override
	protected void init() {
		begin();
		if (cached == null || cached.language != Language.getInstance() || cached.version != BeeDesignerClient.ingredientVersion) {
			cached = new Source();
		}
		if (source != cached) {
			filteredQuery = null;
		}
		source = cached;
		searchField(frameTop + 32, query, value -> {
			query = value;
			page = 0;
			itemPage = 0;
			searchAt = System.currentTimeMillis() + 150;
		});
		contentTop = frameTop + 56;
		rows.add(new Row("ae_type", (x, y, w) -> dropdown(x, y, w, type, source.types.toArray(String[]::new), value -> {
			type = value;
			page = 0;
			itemPage = 0;
		}), 20, true));
		note("", tr("ae_key_help"));
		if (!query.equals(filteredQuery) || !type.equals(filteredType) || observedSize != source.entries.size()) {
			var search = DesignerSearch.compile(query);
			matches = source.entries.stream().filter(entry -> (type.equals("all_key_types") || entry.type.equals(type))
					&& search.matches(entry.namespace, entry.searchable, List.of())).toList();
			filteredQuery = query;
			filteredType = type;
		}
		itemPage = Math.min(itemPage, pages() - 1);
		for (Entry entry : matches.subList(itemPage * 24, Math.min(matches.size(), (itemPage + 1) * 24))) {
			rows.add(new Row("", (x, y, w) -> button(x, y, w, entry.label, () -> select(entry.data))));
		}
		if (!source.ready) {
			note("", tr("status.indexing"));
		}
		if (matches.isEmpty() && source.ready) {
			note("", tr("ae_key_empty"));
		}
		if (source.unavailable > 0) {
			note("", Component.translatable("designer.ae_key_unavailable", source.unavailable));
		}
		observedSize = source.entries.size();
		observedReady = source.ready;
		finishRows();
		button(left, footerY(), 22, Component.literal("‹"), () -> changePage(-1)).active = itemPage > 0;
		pageInput = new DesignerTheme.Input(font, left + 30, footerY() + 5, 32, 20, tr("page_number"));
		pageInput.setMaxLength(6);
		pageInput.setValue(Integer.toString(itemPage + 1));
		pageInput.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("page_number_help")));
		addRenderableWidget(pageInput);
		button(left + 68, footerY(), 42, Component.literal("/" + pages()), () -> {}).active = false;
		button(left + 112, footerY(), 22, Component.literal("›"), () -> changePage(1)).active = itemPage + 1 < pages();
		button(left + contentWidth - 80, footerY(), 80, tr("back"), this::onClose);
		ghost(left, contentTop, contentWidth, Math.max(20, editorBottom() - contentTop),
				value -> value instanceof AEKey || value instanceof GenericStack
						|| value instanceof ItemStack stack && GenericStack.isWrapped(stack), value -> {
			AEKey key;
			if (value instanceof AEKey direct) {
				key = direct;
			} else if (value instanceof GenericStack stack) {
				key = stack.what();
			} else {
				key = GenericStack.unwrapItemStack((ItemStack) value).what();
			}
			JsonObject data = new JsonObject();
			DesignerAeItemKey.selectKey(data, key);
			select(data);
		});
	}

	private int pages() {
		return Math.max(1, (matches.size() + 23) / 24);
	}

	private void changePage(int offset) {
		itemPage = Math.max(0, Math.min(pages() - 1, itemPage + offset));
		page = 0;
		rebuild();
	}

	@Override
	protected boolean keyContent(int key, int scan, int modifiers) {
		if (!hasDropdown() && getFocused() == pageInput && (key == org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER
				|| key == org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER)) {
			try {
				itemPage = Math.max(0, Math.min(pages() - 1, Integer.parseInt(pageInput.getValue()) - 1));
				page = 0;
				rebuild();
			} catch (NumberFormatException exception) {
				status = "designer.error.page_number";
			}
			return true;
		}
		return super.keyContent(key, scan, modifiers);
	}

	private void select(JsonObject data) {
		chosen.accept(data.deepCopy());
		minecraft.setScreen(parent);
	}

	@Override
	public void tick() {
		source.advance();
		if (source != cached || source.entries.size() != observedSize
				|| source.ready != observedReady
				|| searchAt != 0 && System.currentTimeMillis() >= searchAt) {
			searchAt = 0;
			rebuild();
		}
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}

	private record Entry(String type, String namespace, String searchable, Component label, JsonObject data) {
	}

	private static final class Source {
		private final Language language = Language.getInstance();
		private final long version = BeeDesignerClient.ingredientVersion;
		private final List<String> types = new ArrayList<>();
		private final List<Entry> entries = new ArrayList<>();
		private final Set<String> identities = new HashSet<>();
		private final Iterator<?> ingredients = BeeDesignerClient.externalIngredients();
		private boolean ready;
		private int unavailable;

		private Source() {
			types.add("all_key_types");
			var registries = net.minecraft.client.Minecraft.getInstance().level.registryAccess();
			java.util.stream.StreamSupport.stream(appeng.api.stacks.AEKeyTypes.getAll().spliterator(),false).map(AEKeyType::getId).sorted().forEach(id->types.add(id.toString()));
			for (var key : List.of(ResourceTypes.FE, ResourceTypes.SOURCE, ResourceTypes.SOUL, ResourceTypes.AIR,
					ResourceTypes.EXPERIENCE, ResourceTypes.HIGH_VOLTAGE, ResourceTypes.EXTREME_HIGH_VOLTAGE)) {
				try {
					AEKey resolved = Ae2ResourceKeyBridge.resolve(registries, key);
					if (resolved != null) {
						add(resolved);
					}
				} catch (RuntimeException failure) {
					unavailable++;
					ProductiveBeesGenesis.LOGGER.debug("Designer resource key unavailable: {}", key, failure);
				}
			}
		}

		private void advance() {
			if (ready) {
				return;
			}
			long started = System.nanoTime();
			int budget = 256;
			while (ingredients.hasNext() && budget-- > 0 && System.nanoTime() - started < 2_000_000L && entries.size() < 16384) {
				Object value = ingredients.next();
				if (value instanceof AEKey key) {
					add(key);
				} else if (value instanceof GenericStack stack) {
					add(stack.what());
				} else if (value instanceof ItemStack stack && GenericStack.isWrapped(stack)) {
					GenericStack wrapped = GenericStack.unwrapItemStack(stack);
					if (wrapped == null) {
						unavailable++;
					} else {
						add(wrapped.what());
					}
				}
			}
			ready = !ingredients.hasNext() || entries.size() >= 16384;
		}

		private void add(AEKey key) {
			JsonObject data = new JsonObject();
			try {
				DesignerAeItemKey.selectKey(data, key);
				String type = key.getType().getId().toString();
				Component label = key.getDisplayName().copy().append(" · " + type + " · " + key.getId());
				String search = DesignerSearch.indexed(label.getString() + " " + key.getType().getDescription().getString());
				Entry entry = new Entry(type, key.getModId(), search, label, data);
				if (identities.add(data.toString())) {
					entries.add(entry);
				}
			} catch (RuntimeException failure) {
				unavailable++;
				ProductiveBeesGenesis.LOGGER.debug("Designer AE key unavailable for type {}",
						key == null ? "null" : key.getClass().getName(), failure);
			}
		}
	}
}
