package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/** 确认步骤保留设计器布局和背包；回调只提交一次，取消不会丢弃未保存编辑。 */
final class DesignerConfirmationScreen extends DesignerFormScreen {

	private final Consumer<Boolean> result;
	private final Component message;
	private boolean answered;
	private Runnable save;
	private Runnable discard;
	private Runnable cancel;

	DesignerConfirmationScreen(Consumer<Boolean> result, Component title, Component message) {
		super(title);
		this.result = result;
		this.message = message;
		editorParent = net.minecraft.client.Minecraft.getInstance().screen;
	}

	DesignerConfirmationScreen(Component title, Component message, Runnable save, Runnable discard, Runnable cancel) {
		this(confirmed -> {}, title, message);
		this.save = save;
		this.discard = discard;
		this.cancel = cancel;
	}

	@Override
	protected void init() {
		begin();
		contentTop = frameTop + 32;
		for (var line : font.split(message, contentWidth - 8)) {
			StringBuilder text = new StringBuilder();
			line.accept((index, style, codepoint) -> {
				text.appendCodePoint(codepoint);
				return true;
			});
			note("", Component.literal(text.toString()));
		}
		finishRows();
		if (save != null) {
			int w = Math.min(120, (contentWidth - 12) / 3);
			int x = left + contentWidth - w * 3 - 12;
			button(x, footerY(), w, tr("return_editing"), this::onClose);
			button(x + w + 6, footerY(), w, tr("discard_edit_close"), () -> {
				answered = true;
				discard.run();
			});
			primaryButton(x + (w + 6) * 2, footerY(), w, tr("save_edit_close"), () -> {
				answered = true;
				save.run();
			});
			return;
		}
		int w = Math.min(100, (contentWidth - 8) / 2);
		int x = left + contentWidth - w * 2 - 6;
		button(x, footerY(), w, Component.translatable("gui.cancel"), () -> answer(false));
		primaryButton(x + w + 6, footerY(), w, tr(title.getString().equals(tr("remove_recipe").getString()) ? "confirm_remove" : "confirm_action"), () -> answer(true));
	}

	private void answer(boolean confirmed) {
		if (!answered) {
			answered = true;
			result.accept(confirmed);
		}
	}

	@Override
	public void onClose() {
		if (cancel != null && !answered) {
			answered = true;
			cancel.run();
			return;
		}
		answer(false);
	}

	@Override
	protected boolean prepareWindowClose() {
		onClose();
		return false;
	}
}
