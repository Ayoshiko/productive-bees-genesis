package com.ayoshiko.productivebeesgenesis.mek.ae2;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceProductionApi;
import cy.jdkdigital.productivebees.ProductiveBees;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.util.BeeAttributes;
import net.minecraft.server.level.ServerLevel;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;

/** API v3 只生成键描述；AE2 类型仅在已安装 AE2 的兼容入口加载。 */
public final class JdteNativeResourceRecipes {
    private JdteNativeResourceRecipes() {}

    public static List<Object> outputs(ServerLevel level, ConfigurableBee bee, double rolls,
                                       Constructor<?> outputConstructor) throws ReflectiveOperationException {
        var productivity = bee.getAttributeValue(BeeAttributes.PRODUCTIVITY);
        int gene = productivity == null ? 0 : productivity;
        var plan = ResourceProductionApi.plan(ResourceProductionApi.snapshot(level.getServer()),
                new net.minecraft.resources.ResourceLocation(bee.getBeeType()), rolls, gene, level.random.nextLong());
        for (var resource : plan.maximumExact(1).keySet()) {
            if (Ae2ResourceKeyBridge.resolve(level.registryAccess(), resource) == null) {
                throw new IllegalArgumentException("Missing JDTE resource key bridge");
            }
        }
        var outputs = new ArrayList<Object>();
        for (var result : plan.sampleWithRolls(1).exactAmounts().entrySet()) {
            var key = Ae2ResourceKeyBridge.resolve(level.registryAccess(), result.getKey());
            if (key == null) throw new IllegalArgumentException("Unavailable JDTE resource key");
            outputs.add(outputConstructor.newInstance(key.getType().getId(), key.getId(),
                    key.toTag(), result.getValue().longValueExact()));
        }
        return List.copyOf(outputs);
    }
}
