package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerEggs;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerProtocol;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.locale.Language;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.Set;

/** 客户端接收器在客户端初始化时安装，共用协议类不加载 Screen 或 Minecraft。 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
public final class BeeDesignerClient {
	private static Map<String, JsonObject> NAMES = Map.of();
	private static Set<String> RETIRED = Set.of();
	private static DesignerNameBatch nameBatch;
	private static Connection namesConnection;
	private static long namesGeneration = -1;
	private static BeeDesignerScreen screen;
	private static UUID session;
	private static long sequence;
	private static final java.util.ArrayDeque<BeeDesignerProtocol.Request> REQUESTS = new java.util.ArrayDeque<>();
	private static long lastSent;
	private static final Set<Long> CANCELLED = new java.util.LinkedHashSet<>();
	private static boolean namesDirty;
	private static java.util.function.Supplier<java.util.Iterator<?>> externalIngredients = java.util.Collections::emptyIterator;
	static long ingredientVersion;
	private static java.util.function.BiFunction<String, ItemStack, String> recipeViewer = (id, stack) -> "designer.jei_unavailable";

	public static void recipeViewer(java.util.function.BiFunction<String, ItemStack, String> viewer) {
		recipeViewer = viewer;
	}

	static String viewRecipes(String id, ItemStack output) {
		return recipeViewer.apply(id, output.copy());
	}

	public static void externalIngredients(java.util.function.Supplier<java.util.Iterator<?>> source) {
		externalIngredients = source;
		ingredientVersion++;
	}

	static java.util.Iterator<?> externalIngredients() {
		return externalIngredients.get();
	}

	public static void saveDraft(JsonObject document, java.util.function.Consumer<String> status) {
		saveDraft(document, status, () -> {});
	}

	static void saveDraft(JsonObject document, java.util.function.Consumer<String> status, Runnable saved) {
		String snapshot = document.toString();
		long lifecycle = DesignerDraftIndex.lifecycle();
		try {
			BeeDesignerProtocol.checkSize(snapshot);
			DesignerProjectFiles.saveDraft(snapshot, () -> {
				if (lifecycle == DesignerDraftIndex.lifecycle()) {
					DesignerDraftIndex.saved(JsonParser.parseString(snapshot).getAsJsonObject());
				}
				status.accept("designer.status.draft_saved");
				saved.run();
			}, status);
		} catch (RuntimeException exception) {
			status.accept(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
		}
	}

	public static void loadDraft(java.util.function.Consumer<JsonObject> loaded, java.util.function.Consumer<String> status) {
		DesignerProjectFiles.latestDraft(loaded, status);
	}

	private BeeDesignerClient() {
	}

	public static boolean retired(String id) {
		return RETIRED.contains(id);
	}

	static Component itemName(ItemStack stack) {
		if (stack.has(DataComponents.CUSTOM_NAME)) {
			return stack.getHoverName();
		}
		var bee = BeeDesignerEggs.bee(stack);
		if (bee == null) {
			return stack.getHoverName();
		}
		JsonObject names = NAMES.get(bee.toString());
		JsonObject document = screen == null ? null : screen.editingDocument();
		if (document != null && bee.toString().equals(BeeDesignerDocument.text(document, "id", ""))) {
			names = document.getAsJsonObject("names");
		}
		if (names == null) {
			return stack.getHoverName();
		}
		String name = BeeDesignerDocument.text(names, Minecraft.getInstance().options.languageCode,
				BeeDesignerDocument.text(names, "en_us", bee.toString()));
		return Component.translatable("designer.named_bee_egg", name.isBlank() ? bee.toString() : name);
	}

	static Component beeName(String id) {
		JsonObject names = NAMES.get(id);
		JsonObject document = screen == null ? null : screen.editingDocument();
		if (document != null && id.equals(BeeDesignerDocument.text(document, "id", ""))) {
			names = document.getAsJsonObject("names");
		}
		if (names != null) {
			String name = BeeDesignerDocument.text(names, Minecraft.getInstance().options.languageCode,
					BeeDesignerDocument.text(names, "en_us", ""));
			if (!name.isBlank()) {
				return Component.literal(name);
			}
		}
		return BeeDesignerScreen.beeName(id);
	}

	public static void initialize() {
		BeeDesignerProtocol.clientReceiver = BeeDesignerClient::receive;
		com.ayoshiko.productivebeesgenesis.network.FilterConfigResultPayload.receiver = DesignerFilterScreen::receive;
	}

	static void closeEditorWindow() {
		if (screen != null) {
			screen.onClose();
		} else if (Minecraft.getInstance().screen != null) {
			Minecraft.getInstance().screen.onClose();
		}
	}

	public static long request(String action, JsonObject data) {
		if (session == null) {
			return -1;
		}
		String json = data.toString();
		BeeDesignerProtocol.checkSize(json);
		if (REQUESTS.size() >= 32) {
			throw new IllegalStateException("designer.error.queue");
		}
		REQUESTS.addLast(new BeeDesignerProtocol.Request(session, ++sequence, action, json));
		return sequence;
	}

	static void cancelRequest(long sequence) {
		REQUESTS.removeIf(request -> request.sequence() == sequence);
		if (sequence > 0) {
			CANCELLED.add(sequence);
			if (CANCELLED.size() > 128) {
				CANCELLED.remove(CANCELLED.iterator().next());
			}
		}
	}

	private static void receive(BeeDesignerProtocol.Response response, Connection connection) {
		var listener = Minecraft.getInstance().getConnection();
		if (listener == null || listener.getConnection() != connection) {
			return;
		}
		if (response.action().equals("names_reset") || response.action().equals("names")
				|| response.action().equals("names_complete")) {
			receiveNames(response, connection);
			return;
		}
		if (session != null && session.equals(response.session()) && CANCELLED.remove(response.sequence())) {
			return;
		}
		JsonObject data = BeeDesignerProtocol.parse(response.json());
		if (response.action().equals("open")) {
			if (screen != null) {
				screen.disconnected();
			}
			session = response.session();
			sequence = 0;
			REQUESTS.clear();
			CANCELLED.clear();
			DesignerBatchImportScreen.connected();
			screen = new BeeDesignerScreen(data.get("can_publish").getAsBoolean());
			Minecraft.getInstance().setScreen(screen);
			DesignerDraftIndex.ensureLoaded();
			return;
		}
		if (session != null && session.equals(response.session())) {
			DesignerFormScreen importing = Minecraft.getInstance().screen instanceof DesignerFormScreen form ? form : null;
			for (int depth = 0; importing != null && !(importing instanceof DesignerBatchImportScreen) && depth < 16; depth++) {
				importing = importing.editorParent instanceof DesignerFormScreen form ? form : null;
			}
			if (importing instanceof DesignerBatchImportScreen batch && batch.receive(response.sequence(), response.action(), data)) {
				return;
			}
			DesignerFormScreen currentForm = Minecraft.getInstance().screen instanceof DesignerFormScreen form ? form : null;
			for (int depth = 0; currentForm != null && !(currentForm instanceof DesignerCollectionScreen) && depth < 16; depth++) {
				currentForm = currentForm.editorParent instanceof DesignerFormScreen form ? form : null;
			}
			if (currentForm instanceof DesignerCollectionScreen collecting) {
				if (response.action().equals("export_snapshot")) {
					collecting.receive(response.sequence(), data);
					return;
				}
				if (response.action().equals("result") && !data.get("success").getAsBoolean()
						&& collecting.failed(response.sequence(), data.get("message").getAsString())) {
					return;
				}
			}
			if (Minecraft.getInstance().screen instanceof NewBeeScreen copy && response.action().equals("result")
					&& !data.get("success").getAsBoolean() && copy.failed(response.sequence(), data.get("message").getAsString())) {
				return;
			}
			if (response.action().equals("publication_state")) {
				DesignerBatchImportScreen.recordPublicationState(data);
				if (screen != null) {
					screen.receive(response.sequence(), response.action(), data);
				}
				return;
			}
			if (Minecraft.getInstance().screen instanceof NewBeeScreen copy && response.action().equals("copy_id")) {
				copy.receive(response.sequence(), data);
				return;
			}
			if (Minecraft.getInstance().screen instanceof DesignerDeleteScreen deleting
					&& java.util.Set.of("delete_review", "delete_staged", "result").contains(response.action())) {
				deleting.receive(response.sequence(), response.action(), data);
				return;
			}
			if (Minecraft.getInstance().screen instanceof DesignerRecycleScreen recycle && response.action().equals("catalog")) {
				recycle.receive(response.sequence(), data);
				return;
			}
		}
		if (session != null && session.equals(response.session()) && Minecraft.getInstance().screen instanceof DesignerMachinePicker picker) {
			if (response.action().equals("materials")) {
				picker.receive(response.sequence(), data);
				return;
			}
			if (response.action().equals("result") && !data.get("success").getAsBoolean()) {
				picker.failed(response.sequence(), data.get("message").getAsString());
			}
		}
		if (session != null && session.equals(response.session()) && Minecraft.getInstance().screen instanceof DesignerResourcePicker picker) {
			if (response.action().equals("materials")) {
				picker.receive(response.sequence(), data);
				return;
			}
			if (response.action().equals("result") && !data.get("success").getAsBoolean()) {
				picker.failed(response.sequence(), data.get("message").getAsString());
				return;
			}
		}
		if (session != null && session.equals(response.session()) && response.action().equals("history")
				&& Minecraft.getInstance().screen instanceof DesignerHistoryScreen history) {
			history.receive(response.sequence(), data);
			return;
		}
		if (session != null && session.equals(response.session()) && response.action().equals("result")
				&& !data.get("success").getAsBoolean() && Minecraft.getInstance().screen instanceof DesignerHistoryScreen history) {
			history.failed(response.sequence(), data.get("message").getAsString());
			return;
		}
		if (session != null && session.equals(response.session())
				&& (response.action().equals("revision") || response.action().equals("revision_bee"))
				&& Minecraft.getInstance().screen instanceof DesignerRevisionScreen revision) {
			revision.receive(response.sequence(), response.action(), data);
			return;
		}
		if (session != null && session.equals(response.session()) && screen != null) {
			if (response.action().equals("result") && !data.get("success").getAsBoolean()
					&& Minecraft.getInstance().screen instanceof DesignerFormScreen current) {
				current.status = data.get("message").getAsString();
			}
			screen.receive(response.sequence(), response.action(), data);
		}
	}

	private static void receiveNames(BeeDesignerProtocol.Response response, Connection connection) {
		if (namesConnection != connection) {
			namesConnection = connection;
			nameBatch = null;
			namesGeneration = -1;
			NAMES = Map.of();
			RETIRED = Set.of();
			namesDirty = true;
		}
		boolean starting = response.action().equals("names_reset");
		if (response.sequence() < namesGeneration
				|| starting && nameBatch != null && response.sequence() < nameBatch.generation()
				|| !starting && (nameBatch == null || !nameBatch.matches(response.session(), response.sequence()))) {
			return;
		}
		try {
			JsonObject data = BeeDesignerProtocol.parse(response.json());
			if (starting) {
				nameBatch = new DesignerNameBatch(response.session(), response.sequence(), data);
			} else if (response.action().equals("names")) {
				nameBatch.add(data);
			} else {
				DesignerNameBatch.Snapshot snapshot = nameBatch.finish(data);
				NAMES = snapshot.names();
				RETIRED = snapshot.retired();
				namesGeneration = response.sequence();
				nameBatch = null;
				namesDirty = true;
			}
		} catch (RuntimeException failure) {
			nameBatch = null;
			ProductiveBeesGenesis.LOGGER.warn("Rejected bee name batch {}", response.session(), failure);
		}
	}

	private static void refreshLanguage() {
		Language current = Language.getInstance();
		Language base = current instanceof NamesLanguage names ? names.base : current;
		String language = Minecraft.getInstance().options.languageCode;
		Map<String, String> translations = new HashMap<>();
		NAMES.forEach((id, names) -> {
			String locale = names.has(language) ? language : "en_us";
			if (names.has(locale) && !names.get(locale).getAsString().isBlank()) {
				translations.put(BeeDesignerDocument.translationKey(id), names.get(locale).getAsString());
			}
		});
		Language.inject(new NamesLanguage(base, Map.copyOf(translations), language));
	}

	@SubscribeEvent
	public static void opening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
		DesignerFormScreen.opening(event);
	}

	@SubscribeEvent
	public static void frameFinished(net.neoforged.neoforge.client.event.RenderFrameEvent.Post event) {
		DesignerFonts.betweenFrames();
		if (Minecraft.getInstance().screen instanceof DesignerFormScreen) {
			DesignerIngredientCatalog.warmup();
		}
	}

	@SubscribeEvent
	public static void tick(ClientTickEvent.Post event) {
		if (Minecraft.getInstance().screen instanceof DesignerFormScreen form) {
			form.advanceTransition();
			if (screen != null) {
				screen.tickDraft();
			}
		}
		if (!REQUESTS.isEmpty() && Minecraft.getInstance().getConnection() != null && System.nanoTime() - lastSent > 160_000_000L) {
			PacketDistributor.sendToServer(REQUESTS.removeFirst());
			lastSent = System.nanoTime();
		}
		if (namesDirty || !NAMES.isEmpty() && (!(Language.getInstance() instanceof NamesLanguage names)
				|| !names.locale.equals(Minecraft.getInstance().options.languageCode))) {
			namesDirty = false;
			refreshLanguage();
		}
	}

	@SubscribeEvent
	public static void logout(ClientPlayerNetworkEvent.LoggingOut event) {
		DesignerSearch.clear();
		DesignerIngredientCatalog.clear();
		DesignerBeeCatalog.clear();
		if (net.neoforged.fml.ModList.get().isLoaded("ae2")) {
			DesignerAeKeyPicker.clear();
		}
		NAMES = Map.of();
		RETIRED = Set.of();
		nameBatch = null;
		namesConnection = null;
		namesGeneration = -1;
		namesDirty = false;
		DesignerCatalogMetrics.clear();
		DesignerDraftIndex.clear();
		REQUESTS.clear();
		CANCELLED.clear();
		if (screen != null) {
			screen.disconnected();
		}
		screen = null;
		session = null;
		if (Language.getInstance() instanceof NamesLanguage names) {
			Language.inject(names.base);
		}
	}

	@SubscribeEvent
	public static void tagsUpdated(net.neoforged.neoforge.event.TagsUpdatedEvent event) {
		Minecraft.getInstance().execute(() -> {
			DesignerSearch.clear();
			DesignerIngredientCatalog.clear();
		DesignerBeeCatalog.clear();
			if (net.neoforged.fml.ModList.get().isLoaded("ae2")) {
				DesignerAeKeyPicker.clear();
			}
		});
	}

	private static final class NamesLanguage extends Language {
		private final Language base;
		private final Map<String, String> names;
		private final String locale;
		private final Map<String, String> languageData;

		private NamesLanguage(Language base, Map<String, String> names, String locale) {
			this.base = base;
			this.names = names;
			this.locale = locale;
			Map<String, String> data = new HashMap<>(base.getLanguageData());
			data.putAll(names);
			languageData = Map.copyOf(data);
		}

		@Override
		public String getOrDefault(String key, String fallback) {
			return names.getOrDefault(key, base.getOrDefault(key, fallback));
		}

		@Override
		public boolean has(String key) {
			return names.containsKey(key) || base.has(key);
		}

		@Override
		public boolean isDefaultRightToLeft() {
			return base.isDefaultRightToLeft();
		}

		@Override
		public FormattedCharSequence getVisualOrder(FormattedText text) {
			return base.getVisualOrder(text);
		}

		@Override
		public Map<String, String> getLanguageData() {
			return languageData;
		}
	}
}
