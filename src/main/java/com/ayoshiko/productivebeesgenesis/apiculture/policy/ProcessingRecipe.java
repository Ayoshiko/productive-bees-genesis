package com.ayoshiko.productivebeesgenesis.apiculture.policy;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.WorkKey;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductAmount;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.ProductKey;
import java.util.Objects;
import java.util.Set;

/** 配方资格／需求索引，不执行概率采样；真实输出量由后续生产计划提供。 */
public record ProcessingRecipe(WorkKey work, ProductMatcher input, ProductAmount inputPerOperation, Set<ProductKey> possibleOutputs) {
	public ProcessingRecipe {
		Objects.requireNonNull(work); Objects.requireNonNull(input); Objects.requireNonNull(inputPerOperation);
		possibleOutputs = Set.copyOf(possibleOutputs);
		if (work.kind() != WorkKey.Kind.CENTRIFUGE_RECIPE || inputPerOperation.isZero() || possibleOutputs.isEmpty()) {
			throw new IllegalArgumentException("Invalid processing recipe");
		}
	}
}
