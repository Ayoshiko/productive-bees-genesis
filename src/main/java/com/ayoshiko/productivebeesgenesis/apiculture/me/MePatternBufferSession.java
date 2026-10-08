package com.ayoshiko.productivebeesgenesis.apiculture.me;

import com.ayoshiko.productivebeesgenesis.apiculture.core.TerminalPatternBuffer;
import java.util.ArrayList;
import net.minecraft.server.level.ServerPlayer;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;

/** 工作页只保存展示根；实物留在玩家附件，关闭不清缓冲。 */
final class MePatternBufferSession {
	private TerminalPatternBuffer.Snapshot snapshot;
	private long until;
	private MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED, Mode.PATTERN_BUFFER);
	static boolean handles(MeTerminalRequest.Action action) { return action == PATTERN_BUFFER || action == PATTERN_BUFFER_STORE || action == PATTERN_BUFFER_TAKE || action == PATTERN_BUFFER_TAKE_INVENTORY || action == PATTERN_BUFFER_RETURN; }
	MeTerminalView request(ServerPlayer player, MeTerminalRequest request) {
		if (request.page() != 0) return refresh(player, Status.INVALID);
		if (request.action() == PATTERN_BUFFER) return refresh(player, request.row() == -1 ? Status.OK : Status.INVALID);
		// 全部取回只表达当前玩家的回收意图，不依赖可能超出回复预算的完整图标页。
		if (request.action() == PATTERN_BUFFER_RETURN) {
			if (request.row() != -1) return refresh(player, Status.INVALID);
			var result = TerminalPatternBuffer.returnAll(player, player.containerMenu);
			return refresh(player, MeTerminalSession.fluidStatus(result.outcome()));
		}
		if (snapshot == null || !snapshot.current(player) || request.revision() == 0 || request.revision() != view.revision()
				|| player.server.overworld().getGameTime() >= until) return refresh(player, Status.STALE);
		if (request.row() < 0 || request.row() >= TerminalPatternBuffer.SLOTS || request.amount() < 1 || request.amount() > 64) return refresh(player, Status.INVALID);
		boolean insert = request.action() == PATTERN_BUFFER_STORE;
		if (!insert && request.action() != PATTERN_BUFFER_TAKE && request.action() != PATTERN_BUFFER_TAKE_INVENTORY) return refresh(player, Status.INVALID);
		if (insert && !MeTerminalBudget.expensive(player.server)) return refresh(player, Status.BUSY);
		var result = TerminalPatternBuffer.exchange(player, player.containerMenu, snapshot, request.row(), (int) request.amount(), insert, request.action() == PATTERN_BUFFER_TAKE_INVENTORY);
		return refresh(player, MeTerminalSession.fluidStatus(result.outcome()));
	}
	private MeTerminalView refresh(ServerPlayer player, Status status) {
		snapshot = TerminalPatternBuffer.capture(player);
		if (snapshot == null) return view = MeTerminalView.patternStatus(Status.TRANSFER_UNKNOWN, Mode.PATTERN_BUFFER);
		var rows = new ArrayList<Row>(TerminalPatternBuffer.SLOTS);
		for (int i = 0; i < TerminalPatternBuffer.SLOTS; i++) {
			var item = snapshot.item(i); String label = item.isEmpty() ? "" : item.getHoverName().getString();
			if (label.length() > 128) label = label.substring(0, Character.isHighSurrogate(label.charAt(127)) ? 127 : 128);
			rows.add(new Row(Kind.PATTERN_BUFFER_SLOT, item.copyWithCount(1), label, item.getCount(), 0, !item.isEmpty()));
		}
		until = player.server.overworld().getGameTime() + 600;
		return view = new MeTerminalView(MeTerminalBudget.revision(player.server), Mode.PATTERN_BUFFER, status, 0, false, "", 0, "", false, rows);
	}
	void close() { snapshot = null; until = 0; }
}
