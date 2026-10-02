package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.ayoshiko.productivebeesgenesis.util.NumberFormatter;
import java.util.Locale;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 服务端给出的下一工作段参数；展示不重新计算升级公式。 */
final class UpgradePreviewText {
	static Component text(TerminalUpgradePreview p, boolean batch) {
		var text = tr("preview_scope").append("\n");
		if (batch) text.append(tr("preview_batch")).append("\n");
		if (p.status() != TerminalReply.Status.MOVED) return text.append(result(p.status(), 0));
		text.append(tr("preview_movable", p.movable())).append("\n"); var a = p.before(); var b = p.after();
		text.append(tr("preview_time", number(a.timeFactor()), number(b.timeFactor()))).append("\n");
		text.append(tr("preview_energy", compact(a.energyPerTick()), compact(b.energyPerTick()))).append("\n");
		text.append(tr("preview_capacity", compact(a.energyCapacity()), compact(b.energyCapacity()))).append("\n");
		text.append(tr("preview_output", a.parallel(), b.parallel(), number(a.productivity()), number(b.productivity())));
		if (a.stability() != b.stability()) text.append("\n").append(tr("preview_stability", number(a.stability()), number(b.stability())));
		if (a.combBlock() != b.combBlock()) text.append("\n").append(tr(b.combBlock() ? "preview_block_on" : "preview_block_off"));
		if (a.discardByproducts() != b.discardByproducts()) text.append("\n").append(tr(b.discardByproducts() ? "preview_filter_on" : "preview_filter_off"));
		return text;
	}
	private static String number(float value) { return String.format(Locale.ROOT, "%.4g", value); }
	static Component result(TerminalReply.Status status, int moved) { return tr(status == TerminalReply.Status.EMPTY ? "upgrade_empty" : "result." + status.name().toLowerCase(Locale.ROOT), moved); }
	private static String compact(long value) { return NumberFormatter.formatCompact(value); }
	private static MutableComponent tr(String key, Object... args) { return Component.translatable("screen.productivebeesgenesis.network." + key, args); }
	private UpgradePreviewText() { }
}
