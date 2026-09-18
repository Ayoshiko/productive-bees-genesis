package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 蜜脾与蜜脾块因基础 ID 不同而保持独立单位，BEE_TYPE 不忽略蜂型。 */
public record ProductMatcher(Mode mode, ProductKey template) {
	public enum Mode { EXACT, BEE_TYPE, BASE_ITEM }
	public static final String BEE_TYPE = "productivebees:bee_type";
	public ProductMatcher {
		Objects.requireNonNull(mode); Objects.requireNonNull(template);
		if (mode == Mode.BASE_ITEM) template = new ProductKey(template.kind(), template.id(), new CompoundTag());
		if (mode == Mode.BEE_TYPE) {
			Tag bee = template.component(BEE_TYPE).orElseThrow(() -> new IllegalArgumentException("Missing bee type"));
			if (bee.getId() != Tag.TAG_STRING || bee.getAsString().isBlank()) throw new IllegalArgumentException("Invalid bee type");
			var component = new CompoundTag(); component.put(BEE_TYPE, bee);
			template = new ProductKey(template.kind(), template.id(), component);
		}
	}
	public boolean matches(ProductKey key) {
		if (template.kind() != key.kind() || !template.id().equals(key.id())) return false;
		return switch (mode) {
			case EXACT -> template.equals(key);
			case BASE_ITEM -> true;
			case BEE_TYPE -> template.component(BEE_TYPE).equals(key.component(BEE_TYPE));
		};
	}
}
