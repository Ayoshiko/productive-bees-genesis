package com.ayoshiko.productivebeesgenesis.apiculture.me;

import java.util.Objects;

/** 有限的展示偏好，不承载客户端资产或网格身份。 */
public record MeStorageFilter(Sort sort, boolean descending, Content content, Type type) {
	public enum Sort { NAME, AMOUNT, MOD }
	public enum Content { ALL, STORED, CRAFTABLE }
	public enum Type { ALL, ITEM, FLUID, OTHER }
	public static final MeStorageFilter DEFAULT = new MeStorageFilter(Sort.NAME, false, Content.ALL, Type.ALL);
	public MeStorageFilter { Objects.requireNonNull(sort); Objects.requireNonNull(content); Objects.requireNonNull(type); }
}
