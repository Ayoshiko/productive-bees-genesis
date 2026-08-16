package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.ModConfig.FilterMode;
import com.ayoshiko.productivebeesgenesis.config.BalancePreset;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Server configuration screen with a translated popup selector for balance profiles. */
public final class BalanceConfigurationScreen
		extends ConfigurationScreen.ConfigurationSectionScreen {

	private static final String BALANCE_PROFILE_KEY = "balanceProfile";
	private static final String SECTION_KEY = "neoforge.configuration.uitext.section";
	private static final String SECTION_TEXT_KEY = "neoforge.configuration.uitext.sectiontext";

	public BalanceConfigurationScreen(
			Screen parent, ModConfig.Type type, ModConfig modConfig, Component title) {
		super(parent, type, modConfig, title);
	}

	private BalanceConfigurationScreen(
			Context parentContext,
			Screen parent,
			Map<String, Object> valueSpecs,
			String key,
			Set<? extends UnmodifiableConfig.Entry> entrySet,
			Component title) {
		super(parentContext, parent, valueSpecs, key, entrySet, title);
	}

	@Override
	@SuppressWarnings("unchecked")
	protected <T extends Enum<T>> Element createEnumValue(
			String key,
			ModConfigSpec.ValueSpec spec,
			Supplier<T> source,
			Consumer<T> target) {
		if (source.get() instanceof FilterMode) {
			Class<T> enumClass = (Class<T>) spec.getClazz();
			List<T> modes = Arrays.stream(enumClass.getEnumConstants())
					.filter(spec::test)
					.toList();
			return new Element(
					getTranslationComponent(key),
					getTooltipComponent(key, null),
					new OptionInstance<>(
							getTranslationKey(key),
							getTooltip(key, null),
							(caption, value) -> filterModeName((FilterMode) value),
							new Custom<>(modes),
							source.get(),
							newValue -> undoManager.add(value -> {
								target.accept(value);
								onChanged(key);
							}, newValue, value -> {
								target.accept(value);
								onChanged(key);
							}, source.get())));
		}
		if (!BALANCE_PROFILE_KEY.equals(key) || !(source.get() instanceof BalancePreset)) {
			return super.createEnumValue(key, spec, source, target);
		}

		Supplier<BalancePreset> presetSource = (Supplier<BalancePreset>) (Supplier<?>) source;
		Consumer<BalancePreset> presetTarget = (Consumer<BalancePreset>) (Consumer<?>) target;
		List<BalancePreset> presets = Arrays.stream(BalancePreset.values())
				.filter(spec::test)
				.toList();
		BalancePreset selected = presetSource.get();
		Component tooltip = profileTooltip(selected);
		Button selector = Button.builder(presetName(selected), button ->
				openSelector(presets, presetSource, presetTarget))
				.tooltip(Tooltip.create(tooltip))
				.width(Button.DEFAULT_WIDTH)
				.build();
		return new Element(getTranslationComponent(key), tooltip, selector);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected Element createSection(
			String key, UnmodifiableConfig subconfig, UnmodifiableConfig subsection) {
		if (subconfig.isEmpty()) return null;
		Component tooltip = getTooltipComponent(key, null);
		String buttonKey = getTranslationKey(key) + ".button";
		Component buttonName = Component.translatableWithFallback(buttonKey, I18n.get(SECTION_TEXT_KEY));
		return new Element(
				Component.translatable(SECTION_KEY, getTranslationComponent(key)),
				tooltip,
				Button.builder(
						Component.translatable(SECTION_KEY, buttonName),
						button -> minecraft.setScreen(sectionCache.computeIfAbsent(key,
								ignored -> new BalanceConfigurationScreen(
										context,
										this,
										subconfig.valueMap(),
										key,
										subsection.entrySet(),
										getTranslationComponent(key)).rebuild())))
						.tooltip(Tooltip.create(tooltip))
						.width(Button.DEFAULT_WIDTH)
						.build(),
				false);
	}

	private void openSelector(
			List<BalancePreset> presets,
			Supplier<BalancePreset> source,
			Consumer<BalancePreset> target) {
		minecraft.setScreen(new BalancePresetSelectionScreen(
				this,
				presets,
				source.get(),
				selected -> applySelection(source, target, selected)));
	}

	private void applySelection(
			Supplier<BalancePreset> source,
			Consumer<BalancePreset> target,
			BalancePreset selected) {
		BalancePreset previous = source.get();
		if (selected == previous) return;
		undoManager.add(value -> {
			target.accept(value);
			onChanged(BALANCE_PROFILE_KEY);
		}, selected, value -> {
			target.accept(value);
			onChanged(BALANCE_PROFILE_KEY);
		}, previous);
		rebuild();
	}

	private Component profileTooltip(BalancePreset preset) {
		return Component.empty()
				.append(getTooltipComponent(BALANCE_PROFILE_KEY, null))
				.append(Component.literal("\n\n"))
				.append(Component.translatable(preset.getTooltipKey()));
	}

	private static Component presetName(BalancePreset preset) {
		return Component.translatable(preset.getTranslationKey());
	}

	private static Component filterModeName(FilterMode mode) {
		return Component.translatable(
				"productivebeesgenesis.configuration.filter_mode."
						+ mode.name().toLowerCase(Locale.ROOT));
	}
}
