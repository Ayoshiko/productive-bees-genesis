package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.stacks.AEKey;
import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternBatchEditor;
import com.ayoshiko.productivebeesgenesis.apiculture.me.MePatternPlan;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.Status;

/** 多张处理样板共享一次已选定的资源映射，不共享资产或菜单。 */
public record AePatternReplacement(AEKey source, AEKey target) implements MePatternBatchEditor {
	public static Prepared capture(ItemStack pattern, int row, ItemStack sample) {
		var status = AePatternEditor.validate(pattern);
		if (status != Status.OK) return new Prepared(status, null);
		var source = AePatternEditor.sourceKey(pattern, row);
		if (source == null) return new Prepared(Status.STALE, null);
		var target = AePatternEditor.sampleKey(source, sample);
		if (target == null) return new Prepared(Status.PATTERN_SAMPLE_INVALID, null);
		return source.equals(target) ? new Prepared(Status.PATTERN_NO_CHANGE, null) : new Prepared(Status.OK, new AePatternReplacement(source, target));
	}
	@Override public boolean supports(ItemStack pattern) { return AEItems.PROCESSING_PATTERN.is(pattern); }
	@Override public MePatternPlan prepare(ItemStack pattern) { return AePatternEditor.replace(pattern, source, target); }
	@Override public boolean current(ItemStack sample) { return target.equals(AePatternEditor.sampleKey(source, sample)); }
	@Override public String title() {
		String title = source.getDisplayName().getString() + " → " + target.getDisplayName().getString();
		return title.length() <= 256 ? title : title.substring(0, Character.isHighSurrogate(title.charAt(255)) ? 255 : 256);
	}
}
