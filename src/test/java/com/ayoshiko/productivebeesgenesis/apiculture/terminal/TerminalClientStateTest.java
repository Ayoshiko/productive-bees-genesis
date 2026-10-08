package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;

class TerminalClientStateTest {
	@Test void returnOnCloseUsesTheCraftingGenerationAndCannotQueueTwice() {
		var request = state.beginCrafting(CRAFT_RETURN_ON_CLOSE, 19, -1, -1, 0, 0);
		assertEquals(19, request.generation()); assertEquals(1, request.sequence());
		assertNull(state.beginCrafting(CRAFT_RETURN_ON_CLOSE, 19, -1, -1, 0, 200));
		state.close(); state.accept(reply(1, null), 300);
		assertNull(state.result()); assertNull(state.beginCrafting(CRAFT_RETURN_ON_CLOSE, 19, -1, -1, 0, 400));
	}
	@Test void foldingManagementRejectsLateListFramesAndAllowsCraftingAfterLeaseExpiry() {
		state.beginLive(NetworkSelectionSession.Kind.PRODUCTS, "", TerminalSearchRequest.Navigation.FIRST, 0);
		state.acceptLive(new TerminalLiveUpdate(7, session, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, page(1)), 10);
		assertEquals(CANCEL, state.suspend(200).operation());
		state.accept(reply(2, null), 210);
		state.acceptLive(new TerminalLiveUpdate(7, session, 1, 2, 2, TerminalLiveUpdate.Status.READY, false, page(2)), 220);
		assertFalse(state.live()); assertNull(state.view()); assertTrue(state.actionable(10_000));
		assertNotNull(state.beginCrafting(CRAFTING, 0, -1, -1, 0, 10_000));
	}
	@Test void craftingKeepsLiveProductsButOldFramesCannotAcknowledgeTheAssetCommand() {
		state.beginLive(NetworkSelectionSession.Kind.PRODUCTS, "", TerminalSearchRequest.Navigation.FIRST, 0);
		var products = page(1);
		state.acceptLive(new TerminalLiveUpdate(7, session, 1, 1, 1, TerminalLiveUpdate.Status.READY, false, products), 10);
		var request = state.beginCrafting(CRAFT_IN, 19, 8, 35, 16, 200);
		assertEquals(2, request.sequence()); assertEquals(19, request.generation()); assertSame(products, state.view());
		state.acceptLive(new TerminalLiveUpdate(7, session, 1, 1, 2, TerminalLiveUpdate.Status.READY, false, page(2)), 210);
		assertTrue(state.waiting()); assertSame(products, state.view());
		state.acceptLive(new TerminalLiveUpdate(7, session, 1, 2, 3, TerminalLiveUpdate.Status.READY, false, null), 220);
		assertTrue(state.waiting());
		state.accept(new TerminalReply(7, session, 2, TerminalReply.Status.MOVED, 16, 0, null), 230);
		assertSame(products, state.view()); assertTrue(state.actionable(400)); assertEquals(16, state.exchangeResult().moved());
	}
	@Test void closingCraftingRejectsLateRepliesAndCannotReuseItsSequence() {
		var request = state.beginCrafting(CRAFTING, 0, -1, -1, 0, 0);
		assertEquals(1, request.sequence()); state.close();
		state.accept(reply(1, null), 100); assertNull(state.result());
		assertNull(state.beginCrafting(CRAFT_TAKE, 1, -1, -1, 8, 200));
	}
	@Test void previewKeepsTheSamePageWithoutExtendingExpiryOrBecomingAnExchange() {
		state.begin(UPGRADES, -1, -1, 0, 0, 0);
		var page = new TerminalView(NetworkSelectionSession.Kind.UPGRADES, 1, false,
				List.of(new TerminalView.Row("test:apiary", false, "", "", true, List.of())));
		state.accept(reply(1, page), 10);
		state.begin(UPGRADE_PREVIEW_INSTALL, 0, 0, 0, 1, 200); assertSame(page, state.view());
		var preview = new TerminalUpgradePreview(0, 0, 0, 1, true, TerminalReply.Status.EMPTY, 0, null, null);
		state.accept(new TerminalReply(7, session, 2, TerminalReply.Status.OK, 0, 0, null, List.of(), preview), 400);
		assertSame(page, state.view()); assertEquals(preview, state.preview()); assertNull(state.exchangeResult());
		state.tick(5000); assertNull(state.view());
		assertNull(state.begin(UPGRADE_INSTALL_PAGE, 0, 0, 0, 1, 5001));
	}
	private final UUID session = UUID.randomUUID();
	private final TerminalClientState state = new TerminalClientState(7, session);
	private TerminalView page(long generation) {
		return new TerminalView(NetworkSelectionSession.Kind.PRODUCTS, generation, true,
				List.of(new TerminalView.Row("test:item", false, "10", "10", true, List.of())));
	}
	private TerminalReply reply(long sequence, TerminalView view) {
		return new TerminalReply(7, session, sequence, TerminalReply.Status.OK, 0, 0, view);
	}
	@Test void lostAssetReplyCannotReplayAndLateRepliesCannotReplaceNewPage() {
		assertNotNull(state.begin(PRODUCTS, -1, -1, -1, 0, 0));
		state.accept(reply(1, page(1)), 10);
		assertNull(state.begin(TAKE_PRODUCT, 0, 0, 0, 1, 50));
		var action = state.begin(TAKE_PRODUCT, 0, 0, 0, 1, 150);
		assertEquals(2, action.sequence()); assertNull(state.view());
		assertNull(state.begin(TAKE_PRODUCT, 0, 0, 0, 1, 300));
		state.tick(5150); assertEquals(TerminalClientState.Notice.TIMEOUT, state.notice());
		assertNull(state.begin(TAKE_PRODUCT, 0, 0, 0, 1, 5150));
		var refresh = state.begin(PRODUCTS, -1, -1, -1, 0, 5150);
		assertEquals(3, refresh.sequence());
		state.accept(reply(2, page(99)), 5200); assertTrue(state.waiting()); assertNull(state.view());
		state.accept(reply(3, page(2)), 5200); assertEquals(2, state.view().generation());
		state.accept(reply(2, page(99)), 5300); assertEquals(2, state.view().generation());
	}
	@Test void nextPageDoesNotRenewLifetimeAndClosedStateNeverReopens() {
		state.begin(PRODUCTS, -1, -1, -1, 0, 0); state.accept(reply(1, page(1)), 5);
		var next = state.begin(NEXT, -1, -1, -1, 0, 4900); assertEquals(1, next.generation());
		state.accept(reply(2, page(2)), 4950); state.tick(5000);
		assertNull(state.view()); assertEquals(TerminalClientState.Notice.EXPIRED, state.notice());
		state.begin(PRODUCTS, -1, -1, -1, 0, 5100); state.close(); state.accept(reply(3, page(3)), 5200);
		assertNull(state.view()); assertNull(state.result()); assertFalse(state.ready(6000));
	}
	@Test void confirmedExchangeSurvivesReadRefreshButCannotAuthorizeAnotherAction() {
		state.begin(PRODUCTS, -1, -1, -1, 0, 0); state.accept(reply(1, page(1)), 10);
		var request = state.begin(TAKE_PRODUCT, 0, -1, -1, 64, 150);
		assertEquals(-1, request.inventorySlot());
		var moved = new TerminalReply(7, session, 2, TerminalReply.Status.MOVED, 1, 0, null);
		state.accept(moved, 160); assertSame(moved, state.exchangeResult()); assertNull(state.view());
		assertNull(state.begin(TAKE_PRODUCT, 0, -1, -1, 64, 300));
		state.begin(PRODUCTS, -1, -1, -1, 0, 300); state.accept(reply(3, page(2)), 310);
		assertSame(moved, state.exchangeResult()); assertEquals(2, state.view().generation());
		state.begin(TAKE_PRODUCT, 0, -1, -1, 1, 450); assertNull(state.exchangeResult());
		state.tick(5450); assertEquals(TerminalClientState.Notice.TIMEOUT, state.notice()); assertNull(state.exchangeResult());
		state.accept(moved, 5451); assertNull(state.exchangeResult());
		state.close(); assertNull(state.exchangeResult());
	}
	@Test void wrongSessionAndUnrequestedReplyCannotCompletePendingQuery() {
		state.begin(MEMBERS, -1, -1, -1, 0, 0);
		state.accept(new TerminalReply(7, UUID.randomUUID(), 1, TerminalReply.Status.OK, 0, 0, page(1)), 1);
		state.accept(reply(2, page(1)), 2); assertTrue(state.waiting());
		state.accept(reply(1, page(1)), 3); assertFalse(state.waiting());
	}
	@Test void upgradeActionsRequireRefreshAndLostRepliesCannotRepeatAnExchange() {
		var query = state.begin(UPGRADES, -1, -1, 0, 0, 0); assertEquals(0, query.generation());
		var upgrade = new TerminalView.Upgrade(8, "test:block_upgrade", 0, 1, true);
		var page = new TerminalView(NetworkSelectionSession.Kind.UPGRADES, 3, false,
				List.of(new TerminalView.Row("test:apiary", false, "", "", true, List.of(), "", "", List.of(upgrade))));
		state.accept(reply(1, page), 10);
		var request = state.begin(UPGRADE_INSTALL, 0, 8, 7, 1, 200); assertEquals(3, request.generation());
		assertNull(state.view()); state.tick(5200);
		assertNull(state.begin(UPGRADE_INSTALL, 0, 8, 7, 1, 5200));
		assertNotNull(state.begin(UPGRADES, -1, -1, 0, 0, 5200));
		state.accept(new TerminalReply(7, session, 2, TerminalReply.Status.MOVED, 1, 0, null), 5300);
		assertTrue(state.waiting()); assertNull(state.exchangeResult());
	}
}
