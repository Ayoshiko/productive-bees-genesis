package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerCompatibility;
import net.minecraft.network.chat.Component;

/** 单蜂检查与封闭导出预览共用同一依赖说明，列表有明确展示预算。 */
final class DesignerCompatibilityNotes {
	private DesignerCompatibilityNotes() {
	}

	static void add(DesignerFormScreen screen, BeeDesignerCompatibility.Report report) {
		if (!report.requiresGenesis()) {
			screen.note("", DesignerFormScreen.tr("compat.plain"));
			return;
		}
		screen.note("", DesignerFormScreen.tr("compat.requires_genesis"));
		for (var requirement : report.requirements().stream().limit(8).toList()) {
			screen.note("", Component.literal(requirement.bee() + " · ")
					.append(DesignerFormScreen.tr("compat.reason." + requirement.reason()))
					.append(" · " + requirement.detail()));
		}
		if (report.requirements().size() > 8) {
			screen.note("", Component.translatable("designer.compat.more", report.requirements().size() - 8));
		}
	}
}
