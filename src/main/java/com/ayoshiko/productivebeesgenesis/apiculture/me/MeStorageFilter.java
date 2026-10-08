package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.Objects;

/** 有限的展示偏好，不承载客户端资产或网格身份。 */
public record MeStorageFilter(Sort sort, boolean descending, Content content, Type type) {
	public enum Sort implements net.neoforged.neoforge.common.TranslatableEnum {
		NAME, AMOUNT, MOD;
		@Override public net.minecraft.network.chat.Component getTranslatedName() { return label("sort", name()); }
	}
	public enum Content implements net.neoforged.neoforge.common.TranslatableEnum {
		ALL, STORED, CRAFTABLE;
		@Override public net.minecraft.network.chat.Component getTranslatedName() { return label("content", name()); }
	}
	public enum Type implements net.neoforged.neoforge.common.TranslatableEnum {
		ALL, ITEM, FLUID, OTHER, ENERGY, CHEMICAL;
		@Override public net.minecraft.network.chat.Component getTranslatedName() { return label("type", name()); }
	}
	private static net.minecraft.network.chat.Component label(String setting, String name) {
		return net.minecraft.network.chat.Component.translatable("screen.productivebeesgenesis.me_terminal." + setting + "." + name.toLowerCase(java.util.Locale.ROOT));
	}
	public static final MeStorageFilter DEFAULT = new MeStorageFilter(Sort.NAME, false, Content.ALL, Type.ALL);
	public MeStorageFilter { Objects.requireNonNull(sort); Objects.requireNonNull(content); Objects.requireNonNull(type); }
}
