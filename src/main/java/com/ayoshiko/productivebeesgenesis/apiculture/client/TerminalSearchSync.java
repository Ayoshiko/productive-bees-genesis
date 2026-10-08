package com.ayoshiko.productivebeesgenesis.apiculture.client;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;

/** 客户端线程使用的可选字符串入口；生命周期由 JEI 插件提供，终端不加载 JEI 类型。 */
public final class TerminalSearchSync {
	public interface Endpoint {
		String text();
		void text(String value);
		boolean focused();
	}
	private enum State { DISABLED, ACTIVE, UNAVAILABLE, INVALID }
	private static Endpoint endpoint;
	private EditBox decorated;
	private State shown;
	private boolean importing;
	public static void connect(Endpoint value) { endpoint = value; }
	private static boolean enabled() { return ModConfig.CLIENT.terminalPreferences.syncJeiSearch.get(); }
	public void edited(String value) {
		var current = endpoint;
		if (importing || !enabled() || current == null) return;
		try { var previous = current.text(); if (endpoint == current && !value.equals(previous)) current.text(value); }
		catch (RuntimeException | LinkageError failure) { failed(current, failure); }
	}
	public void tick(EditBox search, Component hint) {
		if (search == null) return;
		State state = enabled() ? State.UNAVAILABLE : State.DISABLED;
		var current = endpoint;
		if (state != State.DISABLED && current != null) try {
			state = State.ACTIVE;
			if (current.focused()) {
				// JEI 获取键盘焦点时，旧终端控件可能尚未收到失焦事件。
				search.setFocused(false);
				String value = current.text();
				if (!valid(value)) state = State.INVALID;
				else if (endpoint == current && !value.equals(search.getValue())) {
					importing = true;
					try { search.setValue(value); } finally { importing = false; }
				}
			} else if (search.isFocused()) edited(search.getValue());
			if (endpoint != current) state = State.UNAVAILABLE;
		} catch (RuntimeException | LinkageError failure) { failed(current, failure); state = State.UNAVAILABLE; }
		if (decorated != search || shown != state) {
			var tooltip = hint.copy();
			if (state != State.DISABLED) tooltip.append("\n").append(Component.translatable("screen.productivebeesgenesis.network.terminal.jei_search." + state.name().toLowerCase(java.util.Locale.ROOT)));
			search.setTooltip(Tooltip.create(tooltip)); decorated = search; shown = state;
		}
	}
	private static boolean valid(String value) { return value != null && value.length() <= 64 && value.chars().noneMatch(Character::isISOControl); }
	private static void failed(Endpoint current, Throwable failure) {
		if (endpoint != current) return;
		endpoint = null;
		com.mojang.logging.LogUtils.getLogger().warn("Terminal JEI search link disabled until the next runtime registration", failure);
	}
	public void reset() { decorated = null; shown = null; }
}
