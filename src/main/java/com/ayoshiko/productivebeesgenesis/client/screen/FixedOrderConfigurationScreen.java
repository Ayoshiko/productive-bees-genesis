package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.FactoryTierKey;
import com.ayoshiko.productivebeesgenesis.config.FactoryTierOrderingValidator;
import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.common.ModConfigSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 容量矩阵配置屏幕。
 * <p>
 * NeoForge 原生列表编辑器允许交换和删除元素，会破坏固定等级到数组索引的映射。
 * 此屏幕保留数值编辑能力，但移除增删/上下移动入口，并在关闭前拒绝不满足并行缩放
 * 关系的修改。旧文件中的历史值不会在打开界面时被排序或重写。
 */
public final class FixedOrderConfigurationScreen
		extends ConfigurationScreen.ConfigurationSectionScreen {

	private static final String SECTION_KEY = "neoforge.configuration.uitext.section";
	private static final String SECTION_TEXT_KEY = "neoforge.configuration.uitext.sectiontext";

	public FixedOrderConfigurationScreen(
			Screen parent, ModConfig.Type type, ModConfig modConfig, Component title) {
		super(parent, type, modConfig, title);
	}

	private FixedOrderConfigurationScreen(
			Context parentContext,
			Screen parent,
			Map<String, Object> valueSpecs,
			String key,
			Set<? extends UnmodifiableConfig.Entry> entrySet,
			Component title) {
		super(parentContext, parent, valueSpecs, key, entrySet, title);
	}

	@Override
	@SuppressWarnings({"unchecked", "rawtypes"})
	protected <T> Element createList(
			String key,
			ModConfigSpec.ListValueSpec spec,
			ModConfigSpec.ConfigValue<List<T>> value) {
		Component tooltip = getTooltipComponent(key, null);
		String buttonKey = getTranslationKey(key) + ".button";
		Component buttonName = Component.translatableWithFallback(
				buttonKey, I18n.get(SECTION_TEXT_KEY));
		return new Element(
				Component.translatable(SECTION_KEY, getTranslationComponent(key)),
				tooltip,
				Button.builder(
						Component.translatable(SECTION_KEY, buttonName),
						button -> minecraft.setScreen(sectionCache.computeIfAbsent(key,
								ignored -> new FixedOrderConfigurationListScreen(
										context,
										key,
										getTranslationComponent(key),
										spec,
										value).rebuildScreen())))
						.tooltip(Tooltip.create(tooltip))
						.width(Button.DEFAULT_WIDTH)
						.build(),
				false);
	}

	@Override
	@SuppressWarnings("deprecation")
	protected Element createSection(
			String key, UnmodifiableConfig subconfig, UnmodifiableConfig subsection) {
		if (subconfig.isEmpty()) return null;
		Component tooltip = getTooltipComponent(key, null);
		String buttonKey = getTranslationKey(key) + ".button";
		Component buttonName = Component.translatableWithFallback(
				buttonKey, I18n.get(SECTION_TEXT_KEY));
		return new Element(
				Component.translatable(SECTION_KEY, getTranslationComponent(key)),
				tooltip,
				Button.builder(
						Component.translatable(SECTION_KEY, buttonName),
						button -> minecraft.setScreen(sectionCache.computeIfAbsent(key,
								ignored -> new FixedOrderConfigurationScreen(
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

	/** 固定顺序列表，隐藏会改变索引含义的操作按钮。 */
	private static final class FixedOrderConfigurationListScreen<T>
			extends ConfigurationScreen.ConfigurationListScreen<T> {

		private static final Logger LOGGER = LoggerFactory.getLogger(
				"ProductiveBeesGenesis/ConfigScreen");

		private FixedOrderConfigurationListScreen(
				Context context,
				String key,
				Component title,
				ModConfigSpec.ListValueSpec spec,
				ModConfigSpec.ConfigValue<List<T>> valueList) {
			super(context, key, title, spec, valueList);
		}

		private ConfigurationScreen.ConfigurationSectionScreen rebuildScreen() {
			return rebuild();
		}

		@Override
		protected AbstractWidget createListLabel(int index) {
			String label = FactoryTierKey.groupTiers(key).stream()
					.filter(tier -> tier.groupIndex() == index)
					.map(FactoryTierKey::configKey)
					.findFirst()
					.orElseGet(() -> Integer.toString(index + 1));
			return new FixedOrderListLabelWidget(
					this, Component.literal(label), index);
		}

		@Override
		protected void createAddElementButton() {
			// 矩阵长度由配置规格固定，禁止新增元素改变等级索引。
		}

		@Override
		protected boolean swap(int index, boolean forward) {
			return false;
		}

		@Override
		protected boolean del(int index, boolean forward) {
			return false;
		}

		@Override
		public void onClose() {
			if (changed) {
				List<?> candidate = new ArrayList<>(cfgList);
				FactoryTierOrderingValidator.validateGroup(key, candidate).ifPresent(reason -> {
					LOGGER.warn("拒绝保存容量矩阵 {}：{}；保留本次打开前的值", key, reason);
					cfgList = new ArrayList<>(valueList.get());
					changed = false;
				});
			}
			super.onClose();
		}

		/** 仅显示等级标签；固定矩阵不允许移动或删除元素。 */
		private final class FixedOrderListLabelWidget
				extends ConfigurationScreen.ConfigurationListScreen<T>.ListLabelWidget {

			private FixedOrderListLabelWidget(
					ConfigurationScreen.ConfigurationListScreen<T> owner,
					Component label,
					int index) {
				owner.super(0, 0, 150, 20, label, index);
			}

			@Override
			protected void checkButtons() {
				super.checkButtons();
				upButton.visible = false;
				upButton.active = false;
				downButton.visible = false;
				downButton.active = false;
				delButton.visible = false;
				delButton.active = false;
			}
		}
	}
}
