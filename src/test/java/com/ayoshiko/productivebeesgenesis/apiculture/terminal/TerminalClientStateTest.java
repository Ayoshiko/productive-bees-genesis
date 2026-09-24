package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.TerminalRequest.Operation.*;

class TerminalClientStateTest {
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
	@Test void wrongSessionAndUnrequestedReplyCannotCompletePendingQuery() {
		state.begin(MEMBERS, -1, -1, -1, 0, 0);
		state.accept(new TerminalReply(7, UUID.randomUUID(), 1, TerminalReply.Status.OK, 0, 0, page(1)), 1);
		state.accept(reply(2, page(1)), 2); assertTrue(state.waiting());
		state.accept(reply(1, page(1)), 3); assertFalse(state.waiting());
	}
}
