package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerDocument;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerMachineRecipes;
import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerLifecycle;
import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;


/** 手持编辑会话的页面协调；配方、资源选择和预览分别拥有各自状态。 */
public final class BeeDesignerScreen extends DesignerFormScreen {
	private static final String[] TABS = {"library", "basics", "attributes", "acquisition", "production", "review", "myriad"};
	final boolean canPublish;
	private final DesignerDraftHistory history = new DesignerDraftHistory();
	private final DesignerPreview preview = new DesignerPreview();
	private final DesignerMyriadPage myriadPage = new DesignerMyriadPage();
	private final DesignerLibraryPage library = new DesignerLibraryPage();
	private JsonObject document;
	private JsonObject myriad;
	private JsonObject diagnostics;
	private JsonObject publication;
	private String savedDraft = "";
	private JsonObject observed;
	private String observedFingerprint = "";
	private String applied = "";
	private int tab;
	private long changedAt;
	private long detailRequest;
	private long statusAt;
	private long historyAt;
	private long lastAutoSaveAt;
	private boolean draftSaving;
	private long draftSaveSequence;
	private String savedPending = "";
	private boolean initialized;
	private boolean publishing;
	private boolean advanced;
	private boolean reviewOverview = true;
	private boolean copyAfterLoad;
	private boolean restoreAfterLoad;
	private boolean deletionConfirmed;
	private JsonObject importAfterLoad;
	private JsonArray recoveryAfterLoad;
	private String activeRecovery;
	private String submitted = "";
	private String submittedMyriad = "";
	private Runnable afterMyriadSaved;
	private long myriadSaveRequest = -1;
	private net.minecraft.client.gui.components.Button undoButton;
	private net.minecraft.client.gui.components.Button redoButton;

	public BeeDesignerScreen(boolean canPublish) {
		super(tr("title"));
		this.canPublish = canPublish;
	}

	@Override
	protected void init() {
		begin();
		if (document != null) {
			history.capture(document);
		}
		if (!initialized) {
			initialized = true;
			library.request();
			BeeDesignerClient.request("status", new JsonObject());
		}
		boolean sidebar = hasSidebar();
		boolean wide = hasInlinePreview();
		boolean editing = document != null && tab > 0 && tab < 6;
		if (sidebar) {
			for (int index = 0; index < TABS.length; index++) {
				int selected = index;
				Component label = tr(TABS[index]);
				if (hasDiagnostic(index)) {
					label = label.copy().append(Component.literal("  !").withColor(DesignerTheme.colors().error() & 0xFFFFFF));
				}
				addRenderableWidget(new DesignerTheme.Step(frameLeft + 8, frameTop + 34 + index * 24, 106, index, index == tab,
						label, () -> navigate(selected))).active = index == 0 || index == 6 || document != null;
			}
			left = frameLeft + 122;
			contentWidth = frameWidth - 138;
		} else {
			tabs(left, frameTop + 30, contentWidth, TABS[tab], TABS,
					value -> value.equals("library") || value.equals("myriad") || document != null,
					value -> navigate(java.util.Arrays.asList(TABS).indexOf(value)));
			contentTop = frameTop + 54;
		}
		if (editing) {
			int y = contentTop;
			undoButton = button(left, y, 22, Component.literal("↶"), () -> restore(-1));
			undoButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("undo")));
			redoButton = button(left + 25, y, 22, Component.literal("↷"), () -> restore(1));
			redoButton.setTooltip(net.minecraft.client.gui.components.Tooltip.create(tr("redo")));
			button(left + 50, y, 60, tr("edit_history"), () -> {
				history.capture(document);
				minecraft.setScreen(new DesignerEditHistoryScreen(this, history, value -> {
					document = value;
					diagnostics = null;
				}));
			});
			button(left + 113, y, 54, tr("preview"), () -> editAction("preview"));
			button(left + 170, y, Math.max(40, contentWidth - 170), tr(advanced ? "simple_fields" : "advanced"), () -> editAction("advanced"));
			contentTop += compactHeight() ? 20 : 24;
			if (wide) {
				contentWidth -= 154;
			}
		}
		if (document != null && document.has("_editor_recovery")) {
			note("", tr("recovery_pending_help"));
			rows.add(new Row("", (x, y, w) -> {
				int actionWidth = (w - 4) / 2;
				button(x, y, actionWidth, tr("recovered_json"), this::restoreRecovery);
				button(x + actionWidth + 4, y, actionWidth, tr("discard_recovery"), () ->
						minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> resumeEditing(() -> {
							if (confirmed) {
								document.remove("_editor_recovery");
								activeRecovery = null;
								saveDraft();
								rebuild();
							}
						}), tr("discard_recovery"), tr("discard_recovery_help"))));
			}));
		}
		if (document != null && BeeDesignerLifecycle.deleted(document) && tab > 0 && tab < 6) {
			rows.add(new Row("", (x, rowY, rowW) -> addRenderableWidget(new DesignerBeeButton(x, rowY, rowW, document.get("id").getAsString(), () -> {}))));
			note("", tr(BeeDesignerClient.retired(document.get("id").getAsString()) ? "delete_retired" : "delete_pending"));
			note("", tr("delete_world_assets"));
			note("", tr("delete_application_help"));
			rows.add(new Row("", (x, rowY, rowW) -> {
				int actionWidth = (rowW - 4) / 2;
				button(x, rowY, actionWidth, tr("restore_bee"), () -> {
					loadForRestore(document.get("id").getAsString());
				});
				primaryButton(x + actionWidth + 4, rowY, actionWidth, tr("apply"), this::confirmApply).active = canPublish && !publishing;
			}));
		} else switch (tab) {
			case 0 -> {
				if (publication != null && publication.get("uncertain").getAsBoolean()) {
					note("", tr("unknown_publish_help"));
					rows.add(new Row("", (x, rowY, rowW) -> primaryButton(x, rowY, rowW, tr("reconcile"), this::reconcile)
							.active = canPublish && !publishing));
				}
				library.add(this);
			}
			case 1 -> DesignerBeeFields.appearance(this, document, advanced);
			case 2 -> DesignerBeeFields.attributes(this, document.getAsJsonObject("definition"), advanced);
			case 3 -> recipes(false);
			case 4 -> recipes(true);
			case 5 -> {
				tabs(left, contentTop, contentWidth, reviewOverview ? "overview" : "checks",
						new String[]{"overview", "checks"}, value -> true, value -> {
							reviewOverview = value.equals("overview");
							page = 0;
							rebuild();
						});
				contentTop += 24;
				if (reviewOverview) {
					DesignerOverviewPage.add(this, document, path -> navigateLocation(path, false));
				} else {
					DesignerReviewPage.add(this, document, diagnostics, publication, canPublish, publishing, action -> {
						if (action.equals("confirm_apply")) {
							confirmApply();
						} else {
							submit(action);
						}
					}, this::locate);
				}
			}
			case 6 -> myriad();
			default -> throw new IllegalStateException("Unknown editor page");
		}
		finishRows();
		if (tab == 0) {
			library.positioned(this);
		}
		if (wide && editorBottom() - contentTop >= 180) {
			preview.controls(this, frameLeft + frameWidth - 160, editorBottom() - 66, 148, false);
		}
		int y = footerY();
		if (document != null && tab > 0 && tab < 6) {
			int w = Math.max(30, (contentWidth - 12) / 4);
			button(left, y, w, tr("back"), () -> navigate(tab - 1));
			button(left + w + 4, y, w, tr("save_draft"), this::saveDraft);
			primaryButton(left + (w + 4) * 2, y, w, tr(tab == 5 ? "check" : "next_step"), () -> {
				if (tab == 5) {
					submit("check");
				} else {
					navigate(tab + 1);
				}
			}).active = !publishing;
			button(left + (w + 4) * 3, y, w, Component.translatable("gui.done"), () -> navigate(0));
		} else if (tab != 6) {
			button(left + contentWidth - 100, y, 100, tr("close_editor"), this::onClose);
		}
		if (!wide && document != null && sidebar) {
			button(frameLeft + 8, frameTop + 210, 106, tr("preview"), () -> {
				minecraft.setScreen(new DesignerPreviewScreen(this, document));
			});
		}
	}

	private boolean hasSidebar() {
		return frameWidth >= 760 && frameHeight >= 248;
	}

	private boolean hasInlinePreview() {
		return frameWidth >= 620 && frameHeight >= 248 && document != null && tab > 0 && tab < 6;
	}

	private void editAction(String action) {
			switch (action) {
				case "undo" -> restore(-1);
				case "redo" -> restore(1);
				case "copy" -> minecraft.setScreen(new NewBeeScreen(this, document));
				case "preview" -> minecraft.setScreen(new DesignerPreviewScreen(this, document));
				default -> {
					advanced = !advanced;
					page = 0;
					rebuild();
				}
			}
	}

	private void navigate(int next) {
		if (next < 0 || next >= TABS.length || document == null && next > 0 && next < 6) {
			return;
		}
		if (document != null) {
			history.capture(document);
		}
		int direction = Integer.compare(next, tab);
		tab = next;
		page = 0;
		if (tab == 0) {
			library.request();
		}
		if (tab == 6 && myriad == null) {
			BeeDesignerClient.request("myriad", new JsonObject());
		}
		rebuild();
		animatePage(direction);
	}



	private void recipes(boolean production) {
		DesignerRecipesPage.add(this, document, production, this::openRecipe, this::confirmRemove);
	}

	private void confirmRemove(String id, Runnable remove) {
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			if (confirmed) {
				history.capture(document);
				remove.run();
				history.capture(document);
			}
			minecraft.setScreen(this);
		}, tr("remove_recipe"), Component.literal(id)));
	}

	private void openRecipe(String id) {
		JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(id);
		if (BeeDesignerMachineRecipes.host(recipe) != null) {
			JsonObject source = document.has("machine_sources") ? document.getAsJsonObject("machine_sources").getAsJsonObject(id) : null;
			minecraft.setScreen(new DesignerMachineRecipeScreen(this, recipe, id, source));
		} else {
			minecraft.setScreen(new DesignerRecipeScreen(this, recipe, id));
		}
	}

	private void myriad() {
		boolean uncertain = publication != null && BeeDesignerDocument.text(publication, "phase", "").equals("myriad_uncertain");
		myriadPage.add(this, myriad, canPublish && !publishing, uncertain, this::saveMyriad, this::onClose);
	}

	private void saveMyriad() {
		try {
			submittedMyriad = BeeDesignerDocument.fingerprint(myriad);
			myriadSaveRequest = BeeDesignerClient.request("myriad_save", myriad);
			if (myriadSaveRequest < 0) {
				throw new IllegalStateException("designer.error.designer_session");
			}
			publishing = true;
			statusAt = System.currentTimeMillis();
			status = "designer.status.applying";
			rebuild();
		} catch (RuntimeException exception) {
			status = exception.getMessage();
			afterMyriadSaved = null;
			submittedMyriad = "";
			myriadSaveRequest = -1;
			publishing = false;
		}
	}

	private void submit(String action) {
		if (action.equals("reconcile")) {
			reconcile();
			return;
		}
		try {
			history.capture(document);
			BeeDesignerClient.request(action, document);
			if (action.equals("apply")) {
				submitted = BeeDesignerDocument.fingerprint(document);
			}
			publishing = !action.equals("check");
			status = "designer.status.waiting";
			rebuild();
		} catch (RuntimeException exception) {
			status = exception.getMessage();
		}
	}

	private void reconcile() {
		if (publication != null && BeeDesignerDocument.text(publication, "phase", "").equals("myriad_uncertain")) {
			navigate(6);
			status = "designer.error.myriad_unknown";
			return;
		}
		BeeDesignerClient.request("reconcile", new JsonObject());
		publishing = true;
		status = "designer.phase.reconciling";
		rebuild();
	}

	private void confirmApply() {
		if (BeeDesignerLifecycle.deleted(document)) {
			if (!deletionConfirmed && !BeeDesignerClient.retired(document.get("id").getAsString())) {
				minecraft.setScreen(new DesignerDeleteScreen(this, document.get("id").getAsString()));
				return;
			}
			minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> resumeEditing(() -> {
				if (confirmed) {
					submit("apply");
				}
			}), tr("apply"), tr("delete_apply_help").copy().append("\n\n").append(tr("reload_warning"))));
			return;
		}
		if (document.has("_editor_recovery")) {
			status = "designer.resolve_recovery_before_apply";
			restoreRecovery();
			return;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			resumeEditing(() -> {
				if (confirmed) {
					submit("apply");
				}
			});
		}, tr("apply"), Component.translatable("designer.confirm_apply", document.get("id").getAsString(),
				document.getAsJsonObject("recipes").size()).append("\n\n").append(tr("reload_warning"))));
	}

	public void setDocument(JsonObject document) {
		this.document = document;
		deletionConfirmed = false;
		activeRecovery = null;
		history.reset(document);
		observed = document.deepCopy();
		observedFingerprint = BeeDesignerDocument.fingerprint(document);
		changedAt = System.currentTimeMillis();
		lastAutoSaveAt = changedAt;
		savedDraft = "";
		savedPending = "";
		applied = BeeDesignerDocument.text(document, "baseline", "new").equals("new") ? "" : BeeDesignerDocument.fingerprint(document);
		diagnostics = null;
		tab = 1;
		page = 0;
		status = "designer.status.draft";
		minecraft.setScreen(this);
	}

	public void loadBee(String id) {
		JsonObject query = new JsonObject();
		query.addProperty("id", id);
		query.addProperty("copy", copyAfterLoad);
		query.addProperty("lifecycle", restoreAfterLoad);
		detailRequest = BeeDesignerClient.request("detail", query);
		status = "designer.status.loading";
	}

	void copyBee(String id) {
		if (DesignerDraftIndex.pending(id)) {
			DesignerProjectFiles.draft(id, loaded -> minecraft.setScreen(new NewBeeScreen(this, loaded)), message -> status = message);
			return;
		}
		copyAfterLoad = true;
		loadBee(id);
	}

	void loadForRestore(String id) {
		restoreAfterLoad = true;
		loadBee(id);
		minecraft.setScreen(this);
	}

	void stageDeletion(JsonObject data) {
		setDocument(data);
		applied = data.get("baseline").getAsString();
		deletionConfirmed = true;
		tab = 5;
		status = "designer.status.delete_staged";
		saveDraft();
		rebuild();
	}

	public void receive(long sequence, String action, JsonObject data) {
		switch (action) {
			case "catalog" -> {
				if (!library.receive(sequence, data)) {
					return;
				}
				if (library.resolvedStatus(status)) {
					status = "";
				}
			}
			case "detail" -> {
				if (sequence != detailRequest) {
					return;
				}
				if (copyAfterLoad) {
					copyAfterLoad = false;
					minecraft.setScreen(new NewBeeScreen(this, data));
					return;
				}
				if (restoreAfterLoad) {
					restoreAfterLoad = false;
					setDocument(BeeDesignerLifecycle.restore(data));
					applied = BeeDesignerDocument.fingerprint(data);
					status = "designer.status.restore_pending";
					saveDraft();
					return;
				}
				if (importAfterLoad != null) {
					JsonObject imported = importAfterLoad;
					importAfterLoad = null;
					imported.add("baseline", data.get("baseline"));
					imported.add("definition_path", data.get("definition_path"));
					setDocument(imported);
					applied = BeeDesignerDocument.fingerprint(data);
					status = "designer.status.imported";
					restoreRecovery();
					return;
				}
				setDocument(data);
			}
			case "applied" -> {
				if (document != null && document.get("id").equals(data.get("id"))) {
					String current = BeeDesignerDocument.fingerprint(document);
					if (current.equals(submitted) || current.equals(BeeDesignerDocument.fingerprint(data))) {
						document = data;
						history.reset(document);
					} else {
						document.add("baseline", data.get("baseline"));
						document.add("definition_path", data.get("definition_path"));
						history.capture(document);
					}
					applied = BeeDesignerDocument.fingerprint(data);
				}
				submitted = "";
				publishing = false;
				if (document != null && document.get("id").equals(data.get("id"))) {
					saveDraft();
				}
				status = "designer.status.applied";
				if (BeeDesignerLifecycle.deleted(data)) {
					status = "designer.status.delete_applied";
				}
				library.request();
				BeeDesignerClient.request("status", new JsonObject());
			}
			case "diagnostics" -> {
				diagnostics = data;
				reviewOverview = false;
				status = data.get("valid").getAsBoolean() ? "designer.status.valid" : "designer.check_failed";
				tab = 5;
				page = 0;
			}
			case "bee_data" -> {
				minecraft.setScreen(new DesignerJsonScreen(this, data, tr("runtime_data").getString(), true));
				return;
			}
			case "status" -> {
				publication = data;
				publishing = myriadSaveRequest >= 0 || data.get("busy").getAsBoolean();
			}
			case "myriad" -> {
				if (myriadSaveRequest >= 0 && sequence != myriadSaveRequest) {
					return;
				}
				if (myriad != null && !submittedMyriad.isEmpty() && !BeeDesignerDocument.fingerprint(myriad).equals(submittedMyriad)) {
					myriad.add("baseline", data.get("baseline"));
				} else {
					myriad = data;
				}
				publishing = false;
			}
			case "exported" -> {
				publishing = false;
				status = Component.translatable("designer.status.exported", data.get("path").getAsString()).getString();
			}
			case "result" -> {
				if (!data.get("success").getAsBoolean() && library.failed(sequence, data.get("message").getAsString())) {
					status = data.get("message").getAsString();
					if (minecraft.screen == this) {
						rebuild();
					}
					return;
				}
				if (myriadSaveRequest >= 0 && sequence != myriadSaveRequest) {
					return;
				}
				status = data.get("message").getAsString();
				if (status.equals("designer.error.delete_confirmation")) {
					deletionConfirmed = false;
				}
				if (!status.equals("designer.status.applying") && !status.equals("designer.status.exporting")) {
					publishing = false;
					if (!data.get("success").getAsBoolean()) {
						if (sequence == detailRequest) {
							copyAfterLoad = false;
							restoreAfterLoad = false;
							importAfterLoad = null;
						}
						afterMyriadSaved = null;
						submittedMyriad = "";
						myriadSaveRequest = -1;
						BeeDesignerClient.request("status", new JsonObject());
					}
				}
				if (data.get("success").getAsBoolean() && status.equals("designer.status.myriad_saved")) {
					submittedMyriad = "";
					myriadSaveRequest = -1;
					Runnable saved = afterMyriadSaved;
					afterMyriadSaved = null;
					if (saved != null && !myriadDirty() && minecraft.screen == this) {
						saved.run();
						return;
					}
				}
			}
			default -> {
				return;
			}
		}
		if (minecraft.screen == this) {
			rebuild();
		}
	}

	private void locate(String path) {
		navigateLocation(path, true);
	}

	void navigateLocation(String path, boolean error) {
		if (document == null) {
			return;
		}
		tab = DesignerFieldNavigation.tab(document, path);
		advanced = advanced || DesignerFieldNavigation.advanced(path);
		page = 0;
		rebuild();
		String recipeId = DesignerFieldNavigation.recipeId(document, path);
		DesignerFormScreen target = this;
		String anchor = path;
		if (recipeId != null) {
			JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(recipeId);
			if (BeeDesignerMachineRecipes.host(recipe) != null) {
				JsonObject source = document.has("machine_sources") ? document.getAsJsonObject("machine_sources").getAsJsonObject(recipeId) : null;
				openLocated(new DesignerMachineRecipeScreen(this, recipe, recipeId, source), path, path, error);
				return;
			} else if (DesignerFieldNavigation.production(recipe)) {
				String node = productionNode(recipeId);
				if (node != null) {
					openLocated(new DesignerProductionScreen(this, document, node), productionLocation(node, recipeId, path), path, error);
					return;
				} else if (path.equals("recipes/" + recipeId)) {
					anchor = recipeId;
				}
			} else if (path.equals("recipes/" + recipeId)) {
				anchor = recipeId;
			}
		} else if (path.startsWith("production_nodes/")) {
			String node = path.substring("production_nodes/".length()).split("/", 2)[0];
			if (BeeDesignerDocument.object(document, "production_nodes").has(node)) {
				openLocated(new DesignerProductionScreen(this, document, node), path, path, error);
				return;
			}
		} else if (path.equals("definition") || path.equals("id")) {
			anchor = "identity";
		} else if (path.equals("acquisition")) {
			anchor = "acquisition_recipes";
		} else if (path.equals("production")) {
			anchor = "production_nodes";
		}
		if (!target.focusLocation(anchor, error)) {
			String fallback = recipeId == null ? "definition/advanced" : "recipes/" + recipeId + "/advanced";
			target.focusLocation(fallback, error);
		}
		target.status = "designer.located|" + DesignerFieldNavigation.breadcrumb(document, path).getString();
	}

	private void openLocated(DesignerFormScreen target, String anchor, String path, boolean error) {
		leaveTo(() -> {
			minecraft.setScreen(target);
			target.focusLocation(anchor, error);
			target.status = "designer.located|" + DesignerFieldNavigation.breadcrumb(document, path).getString();
		});
	}

	private String productionLocation(String node, String recipeId, String path) {
		String root = "production_nodes/" + node;
		String suffix = path.substring(("recipes/" + recipeId).length());
		JsonObject recipe = document.getAsJsonObject("recipes").getAsJsonObject(recipeId);
		if (suffix.startsWith("/outputs/") || suffix.startsWith("/results/")) {
			return root + (recipe.get("type").getAsString().startsWith("productivebeesgenesis:") ? "/resources/" : "/items/")
					+ suffix.substring(suffix.indexOf('/', 1) + 1);
		}
		if (suffix.contains("processing")) {
			return root + "/time";
		}
		return root + suffix;
	}

	private String productionNode(String recipeId) {
		for (var entry : BeeDesignerDocument.object(document, "production_nodes").entrySet()) {
			for (var value : entry.getValue().getAsJsonObject().getAsJsonArray("recipe_ids")) {
				if (value.getAsString().equals(recipeId)) {
					return entry.getKey();
				}
			}
		}
		return null;
	}

	private boolean hasDiagnostic(int page) {
		if (document == null || diagnostics == null || !BeeDesignerDocument.fingerprint(document).equals(diagnostics.get("fingerprint").getAsString())) {
			return false;
		}
		for (var value : diagnostics.getAsJsonArray("issues")) {
			JsonObject issue = value.getAsJsonObject();
			if (issue.get("severity").getAsString().equals("error") && DesignerFieldNavigation.tab(document, issue.get("path").getAsString()) == page) {
				return true;
			}
		}
		return false;
	}

	private void restore(int direction) {
		history.capture(document);
		document = history.move(direction);
		diagnostics = null;
		rebuild();
	}

	private void saveDraft() {
		saveDraft(() -> {});
	}

	private void saveDraft(Runnable saved) {
		JsonObject target = document;
		String snapshot = document.toString();
		DesignerFormScreen current = minecraft.screen instanceof DesignerFormScreen form ? form : this;
		String pending = DesignerDraftSnapshot.pendingState(this, current);
		JsonObject stored = DesignerDraftSnapshot.capture(this, current, document);
		stored.addProperty("baseline", applied.isEmpty() ? "new" : applied);
		draftSaving = true;
		long sequence = ++draftSaveSequence;
		lastAutoSaveAt = System.currentTimeMillis();
		BeeDesignerClient.saveDraft(stored, message -> {
			if (sequence != draftSaveSequence) {
				return;
			}
			draftSaving = false;
			if (document != target) {
				return;
			}
			if (!publishing) {
				status = message;
			}
			if (message.equals("designer.status.draft_saved") && target.toString().equals(snapshot)) {
				savedDraft = snapshot;
				savedPending = pending;
			}
		}, () -> {
			if (sequence == draftSaveSequence && document == target && target.toString().equals(snapshot)
					&& minecraft.player != null && minecraft.screen == this) {
				saved.run();
			}
		});
	}

	private boolean dirty() {
		return document != null && !(document.equals(observed) ? observedFingerprint : BeeDesignerDocument.fingerprint(document)).equals(applied);
	}

	private boolean unfinishedDraft() {
		return document != null && (dirty() || document.has("_editor_recovery"));
	}

	void changeDocument(Runnable action) {
		if (document == null || document.toString().equals(savedDraft) || !unfinishedDraft()) {
			action.run();
			return;
		}
		minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
			resumeEditing(() -> {
				if (confirmed) {
					saveDraft(action);
				}
			});
		}, tr("switch_draft"), tr("switch_draft_help")));
	}

	private void resumeEditing(Runnable action) {
		leaveTo(() -> {
			minecraft.setScreen(this);
			action.run();
		});
	}

	@Override
	public void tick() {
		long now = System.currentTimeMillis();
		if (tab == 0) {
			library.tick(this);
		}
		if (undoButton != null) {
			undoButton.active = history.canUndo() && !publishing;
			redoButton.active = history.canRedo() && !publishing;
		}
		if (document != null) {
			preview.update(document);
		}
		if (publishing && now - statusAt >= 1500) {
			statusAt = now;
			BeeDesignerClient.request("status", new JsonObject());
		}
	}

	void tickDraft() {
		long now = System.currentTimeMillis();
		if (document != null) {
			if (activeRecovery != null && minecraft.screen == this) {
				activeRecovery = null;
				restoreRecovery();
			}
			boolean changed = !document.equals(observed);
			if (changed) {
				observed = document.deepCopy();
				observedFingerprint = BeeDesignerDocument.fingerprint(document);
				changedAt = now;
				historyAt = now;
			}
			if (historyAt != 0 && now - historyAt > 500) {
				historyAt = 0;
				history.capture(document);
			}
			if (DesignerPreferences.enabled("auto_save") && !draftSaving
					&& now - lastAutoSaveAt >= DesignerPreferences.number("auto_save_ms")) {
				String current = document.toString();
				String pending = minecraft.screen instanceof DesignerFormScreen form ? DesignerDraftSnapshot.pendingState(this, form) : "";
				if ((unfinishedDraft() || !pending.isEmpty() || !savedPending.isEmpty())
						&& (!current.equals(savedDraft) || !pending.equals(savedPending))) {
					saveDraft();
				} else {
					lastAutoSaveAt = now;
				}
			}
		}
	}

	void draftsUpdated() {
		if (tab == 0) {
			library.request();
			rebuild();
		}
	}

	@Override
	protected void renderContent(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderContent(graphics, mouseX, mouseY, partialTick);
		if (tab == 0) {
			library.renderFooter(this, graphics);
		}
		if (hasInlinePreview()) {
			int x = frameLeft + frameWidth - 160;
			preview.render(graphics, document, x, contentTop, 148, Math.max(32, Math.min(editorBottom() - contentTop - 70, 230)), mouseX, mouseY);
		}
		renderDropdown(graphics, mouseX, mouseY);
	}

	@Override
	protected Component idleStatus() {
		return document == null ? Component.empty() : tr(unfinishedDraft() ? "unapplied_changes" : "active_revision");
	}

	@Override
	protected boolean dragContent(double x, double y, int button, double dx, double dy) {
		if (isWindowDragging()) {
			return super.dragContent(x, y, button, dx, dy);
		}
		if (!hasDropdown() && !isInventoryDragging() && hasInlinePreview()
				&& x > frameLeft + frameWidth - 160 && x < frameLeft + frameWidth - 12
				&& y > contentTop && y < Math.min(editorBottom() - 70, contentTop + 230)) {
			preview.rotate(dx, dy);
			return true;
		}
		return super.dragContent(x, y, button, dx, dy);
	}

	@Override
	protected boolean usesRowScrollbar() {
		return true;
	}

	@Override
	protected boolean scrollContent(double x, double y, double horizontal, double vertical) {
		if (tab == 0 && !hasDropdown() && x >= left && x < left + contentWidth + 10
				&& y >= contentTop && y < editorBottom()) {
			if (super.scrollContent(x, y, horizontal, vertical)) {
				return true;
			}
			return true;
		}
		return super.scrollContent(x, y, horizontal, vertical);
	}

	@Override
	public boolean keyPressed(int key, int scan, int modifiers) {
		if (isLeaving()) {
			return true;
		}
		if (!hasDropdown() && document != null && hasControlDown() && key == org.lwjgl.glfw.GLFW.GLFW_KEY_S) {
			saveDraft();
			return true;
		}
		if (!hasDropdown() && document != null && hasControlDown() && !(getFocused() instanceof EditBox)) {
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Z) {
				restore(hasShiftDown() ? 1 : -1);
				return true;
			}
			if (key == org.lwjgl.glfw.GLFW.GLFW_KEY_Y) {
				restore(1);
				return true;
			}
		}
		return super.keyPressed(key, scan, modifiers);
	}

	@Override
	public void onClose() {
		if (myriadDirty() && canPublish) {
			minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
				resumeEditing(() -> {
					if (confirmed) {
						afterMyriadSaved = this::onClose;
						saveMyriad();
					}
				});
			}, tr("save_myriad"), tr("close_myriad_help")));
			return;
		}
		if (document != null && (unfinishedDraft() || !savedPending.isEmpty() || draftSaving)) {
			minecraft.setScreen(new DesignerConfirmationScreen(confirmed -> {
				resumeEditing(() -> {
					if (confirmed) {
						saveDraft(this::closeEditor);
					}
				});
			}, tr("close_draft"), tr("close_draft_help")));
		} else {
			closeEditor();
		}
	}

	private boolean myriadDirty() {
		return myriad != null && !BeeDesignerDocument.fingerprint(myriad).equals(BeeDesignerDocument.text(myriad, "baseline", ""));
	}

	private void closeEditor() {
		leaveTo(() -> {
			preview.clear();
			BeeDesignerClient.request("close", new JsonObject());
			minecraft.setScreen(null);
		});
	}

	void disconnected() {
		preview.clear();
	}

	void importDocument(JsonObject imported) {
		JsonObject clean = imported.deepCopy();
		if (clean.has("_editor_recovery") && !clean.get("_editor_recovery").isJsonArray()) {
			status = "designer.error.recovery_target";
			return;
		}
		recoveryAfterLoad = clean.has("_editor_recovery") ? clean.remove("_editor_recovery").getAsJsonArray() : null;
		if (recoveryAfterLoad != null) {
			for (var value : recoveryAfterLoad) {
				if (value.isJsonObject() && !value.getAsJsonObject().has("id")) {
					value.getAsJsonObject().addProperty("id", java.util.UUID.randomUUID().toString());
				}
			}
		}
		String id = imported.get("id").getAsString();
		if (cy.jdkdigital.productivebees.setup.BeeReloadListener.INSTANCE.getData(net.minecraft.resources.ResourceLocation.parse(id)) != null) {
			importAfterLoad = clean;
			loadBee(id);
			minecraft.setScreen(this);
		} else {
			JsonObject copy = clean;
			copy.addProperty("baseline", "new");
			setDocument(copy);
			status = "designer.status.imported";
			restoreRecovery();
		}
	}

	private void restoreRecovery() {
		if (recoveryAfterLoad != null && !recoveryAfterLoad.isEmpty()) {
			document.add("_editor_recovery", recoveryAfterLoad);
			recoveryAfterLoad = null;
		}
		if (!document.has("_editor_recovery") || document.getAsJsonArray("_editor_recovery").isEmpty()) {
			return;
		}
		leaveTo(() -> {
			minecraft.setScreen(this);
			JsonArray recovery = document.getAsJsonArray("_editor_recovery");
			for (var value : recovery) {
				if (!value.isJsonObject()) {
					continue;
				}
				JsonObject entry = value.getAsJsonObject();
				try {
					String id = BeeDesignerDocument.text(entry, "id", "");
					String kind = BeeDesignerDocument.text(entry, "kind", "");
					if (kind.equals("production")) {
						DesignerProductionScreen production = new DesignerProductionScreen(this, document, entry.get("node").getAsString());
						production.restoreDraft(entry);
						production.recovery(id, () -> resolveRecovery(id));
						activeRecovery = id;
						minecraft.setScreen(production);
						for (var nestedValue : recovery) {
							if (!nestedValue.isJsonObject()) {
								continue;
							}
							JsonObject nested = nestedValue.getAsJsonObject();
							if (id.equals(BeeDesignerDocument.text(nested, "parent", "")) && nested.has("path")) {
								JsonObject target = production.recoveryTarget(nested.getAsJsonArray("path"));
								if (target != null && BeeDesignerDocument.text(nested, "kind", "").equals("json")) {
									openRecoveredJson(production, target, nested);
									break;
								}
							}
						}
						return;
					}
					JsonObject target = entry.has("path") && !entry.has("parent")
							? DesignerDraftSnapshot.target(document, entry.getAsJsonArray("path")) : null;
					if (target != null && kind.equals("json")) {
						activeRecovery = id;
						openRecoveredJson(this, target, entry);
						return;
					}
				} catch (RuntimeException failure) {
					activeRecovery = null;
					ProductiveBeesGenesis.LOGGER.warn("Could not restore bee draft entry; original input retained", failure);
					minecraft.setScreen(this);
				}
			}
			status = "designer.error.recovery_target";
		});
	}

	private void openRecoveredJson(DesignerFormScreen parent, JsonObject target, JsonObject entry) {
		String id = entry.get("id").getAsString();
		DesignerJsonScreen json = new DesignerJsonScreen(parent, target, tr("recovered_json").getString());
		json.restoreText(entry.get("text").getAsString());
		json.recovery(id, () -> resolveRecovery(id));
		minecraft.setScreen(json);
	}

	private void resolveRecovery(String id) {
		if (document.has("_editor_recovery")) {
			JsonArray recovery = document.getAsJsonArray("_editor_recovery");
			recovery.asList().removeIf(value -> value.isJsonObject()
					&& (id.equals(BeeDesignerDocument.text(value.getAsJsonObject(), "id", ""))
					|| id.equals(BeeDesignerDocument.text(value.getAsJsonObject(), "parent", ""))));
			if (recovery.isEmpty()) {
				document.remove("_editor_recovery");
			}
		}
	}

	void exportLocal() {
		DesignerProjectFiles.export(document, path -> status = Component.translatable("designer.status.exported", path.toString()).getString(),
				message -> status = message);
	}

	JsonObject editingDocument() {
		return document;
	}

	void runtimeData() {
		if (document == null || BeeDesignerDocument.text(document, "baseline", "new").equals("new")) {
			status = "designer.draft_only";
			return;
		}
		JsonObject query = new JsonObject();
		query.add("id", document.get("id"));
		BeeDesignerClient.request("bee_data", query);
	}

	@Override
	public void removed() {
		super.removed();
		preview.clear();
	}

	static Component beeName(String id) {
		if (id.equals("minecraft:bee")) {
			return Component.translatable("entity.minecraft.bee");
		}
		var beeId = net.minecraft.resources.ResourceLocation.tryParse(id);
		if (beeId == null) {
			return Component.literal(id);
		}
		if (net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.containsKey(beeId)) {
			return net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.get(beeId).getDescription();
		}
		String path = beeId.getPath();
		String key = BeeDesignerDocument.translationKey(id);
		return net.minecraft.locale.Language.getInstance().has(key) ? Component.translatable(key) : Component.literal(path);
	}
}
