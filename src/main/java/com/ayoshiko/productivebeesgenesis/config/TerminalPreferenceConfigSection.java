package com.ayoshiko.productivebeesgenesis.config;

import com.ayoshiko.productivebeesgenesis.apiculture.me.MeStorageFilter;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.common.TranslatableEnum;

/** 固定大小的本地显示与交互偏好；不保存库存、菜单或服务器身份。 */
public final class TerminalPreferenceConfigSection {
	public enum BeeOrder implements TranslatableEnum {
		POSITION, QUANTITY_DESC, QUANTITY_ASC;
		@Override public Component getTranslatedName() {
			return Component.translatable("screen.productivebeesgenesis.network.terminal." + switch (this) {
				case POSITION -> "sort_id"; case QUANTITY_DESC -> "sort_quantity_desc"; case QUANTITY_ASC -> "sort_quantity_asc";
			});
		}
	}
	public final ModConfigSpec.BooleanValue rememberSearch, autoFocus, meSource, meDescending, syncJeiSearch, notifyCraftingFinished, returnCraftingOnClose, pinCraftingFinished, wirelessPickBlock, wirelessRestock;
	public final ModConfigSpec.ConfigValue<String> beeSearch, meSearch;
	public final ModConfigSpec.EnumValue<BeeOrder> beeSort;
	public final ModConfigSpec.EnumValue<MeStorageFilter.Sort> meSort;
	public final ModConfigSpec.EnumValue<MeStorageFilter.Content> meContent;
	public final ModConfigSpec.EnumValue<MeStorageFilter.Type> meType;
	private static final String KEY = "productivebeesgenesis.configuration.";
	TerminalPreferenceConfigSection(ModConfigSpec.Builder builder) {
		builder.comment("蜂业终端本地显示与交互偏好；各终端共享，不包含资产或服务器身份").push("terminal_preferences");
		rememberSearch = builder.translation(KEY + "terminalRememberSearch").define("terminalRememberSearch", true);
		autoFocus = builder.translation(KEY + "terminalAutoFocus").define("terminalAutoFocus", false);
		syncJeiSearch = builder.translation(KEY + "terminalSyncJeiSearch").define("terminalSyncJeiSearch", false);
		notifyCraftingFinished = builder.translation(KEY + "terminalNotifyCraftingFinished").define("terminalNotifyCraftingFinished", false);
		returnCraftingOnClose = builder.translation(KEY + "terminalReturnCraftingOnClose").define("terminalReturnCraftingOnClose", false);
		pinCraftingFinished = builder.translation(KEY + "terminalPinCraftingFinished").define("terminalPinCraftingFinished", false);
		wirelessPickBlock = builder.translation(KEY + "terminalWirelessPickBlock").define("terminalWirelessPickBlock", false);
		wirelessRestock = builder.translation(KEY + "terminalWirelessRestock").define("terminalWirelessRestock", false);
		meSource = builder.translation(KEY + "terminalMeSource").define("terminalMeSource", false);
		beeSearch = search(builder, "terminalBeeSearch"); meSearch = search(builder, "terminalMeSearch");
		beeSort = builder.translation(KEY + "terminalBeeSort").defineEnum("terminalBeeSort", BeeOrder.POSITION);
		meSort = builder.translation(KEY + "terminalMeSort").defineEnum("terminalMeSort", MeStorageFilter.Sort.NAME);
		meDescending = builder.translation(KEY + "terminalMeDescending").define("terminalMeDescending", false);
		meContent = builder.translation(KEY + "terminalMeContent").defineEnum("terminalMeContent", MeStorageFilter.Content.ALL);
		meType = builder.translation(KEY + "terminalMeType").defineEnum("terminalMeType", MeStorageFilter.Type.ALL);
		builder.pop();
	}
	private static ModConfigSpec.ConfigValue<String> search(ModConfigSpec.Builder builder, String name) {
		return builder.translation(KEY + name).define(name, "", value -> value instanceof String text && text.length() <= 64 && text.chars().noneMatch(Character::isISOControl));
	}
	public MeStorageFilter meFilter() { return new MeStorageFilter(meSort.get(), meDescending.get(), meContent.get(), meType.get()); }
	public void storeMe(String query, MeStorageFilter filter) {
		meSearch.set(rememberSearch.get() ? query : ""); meSort.set(filter.sort()); meDescending.set(filter.descending()); meContent.set(filter.content()); meType.set(filter.type());
	}
	public boolean forgetDisabledSearches() {
		if (rememberSearch.get()) return false;
		boolean changed = !beeSearch.get().isEmpty() || !meSearch.get().isEmpty();
		if (changed) { beeSearch.set(""); meSearch.set(""); }
		return changed;
	}
}
