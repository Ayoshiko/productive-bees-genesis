package com.ayoshiko.productivebeesgenesis.apiary;

import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneValue;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.ayoshiko.productivebeesgenesis.apiary.FiniteSamplingOracle.Mass;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneSamplerFiniteDistributionTest {
    private static final GeneSampleProfile LOW = new GeneSampleProfile(GeneValue.PRODUCTIVITY_NORMAL,
            GeneValue.ENDURANCE_WEAK, GeneValue.TEMPER_NORMAL, GeneValue.BEHAVIOR_DIURNAL,
            GeneValue.WEATHER_TOLERANCE_NONE);
    private static final GeneSampleProfile HIGH = new GeneSampleProfile(GeneValue.PRODUCTIVITY_VERY_HIGH,
            GeneValue.ENDURANCE_STRONG, GeneValue.TEMPER_HOSTILE, GeneValue.BEHAVIOR_NOCTURNAL,
            GeneValue.WEATHER_TOLERANCE_ANY);
    private static final Method EXACT = method("generateExactSamples", GeneSampler.SampleBatch.class,
            float.class, RandomSource.class, boolean.class, boolean.class);
    private static final Method AGGREGATED = method("generateAggregatedSamples", GeneSampler.SampleBatch.class,
            long.class, RandomSource.class, boolean.class, boolean.class);
    private static final Method HITS = method("approximateHitCount", long.class, float.class, RandomSource.class);
    private static final Method ENTRY = method("generateGeneSamplesInto", List.class, GeneSampler.SampleBatch.class,
            ResourceLocation.class, float.class, RandomSource.class, boolean.class, boolean.class);
    private static final Field COUNTS = field("sampledCounts");

    @Test
    void mixedExactGenesAndBothPluginsMatchAllJointOutcomes() {
        for (boolean typeOnly : new boolean[]{false, true}) {
            for (boolean fullPurity : new boolean[]{false, true}) {
                var actual = FiniteSamplingOracle.enumerate(decisions -> {
                    var sampler = new GeneSampler();
                    invoke(EXACT, sampler, batch(LOW, 1, HIGH, 1), 0.5F, random(decisions), typeOnly, fullPurity);
                    return counts(sampler);
                });
                var expected = convolve(singleEvent(LOW, typeOnly, fullPurity),
                        singleEvent(HIGH, typeOnly, fullPurity));
                assertEquals(expected, actual);
            }
        }
    }

    @Test
    void all128HitCountsMatchTheClosedFormMassFromActualSingleEventTransitions() {
        var transition = FiniteSamplingOracle.enumerate(decisions -> {
            var sampler = new GeneSampler();
            invoke(EXACT, sampler, batch(LOW, 1, null, 0), 0.5F, random(decisions), true, true);
            return (int) counts(sampler).values().stream().mapToLong(Long::longValue).sum();
        });
        Map<Integer, Mass> actual = Map.of(0, Mass.ONE);
        for (int event = 0; event < 128; event++) {
            Map<Integer, Mass> next = new LinkedHashMap<>();
            actual.forEach((count, mass) -> transition.forEach((added, probability) ->
                    FiniteSamplingOracle.add(next, count + added, mass.multiply(probability))));
            actual = next;
        }
        // 闭式二项式系数独立于逐次转移卷积，并包括 RandomSource float 的 <= 边界。
        Mass hit = Mass.of(8_388_609, 16_777_216);
        Mass miss = Mass.of(8_388_607, 16_777_216);
        Map<Integer, Mass> expected = new LinkedHashMap<>();
        BigInteger choose = BigInteger.ONE;
        for (int count = 0; count <= 128; count++) {
            expected.put(count, new Mass(choose, BigInteger.ONE)
                    .multiply(hit.pow(count)).multiply(miss.pow(128 - count)));
            if (count < 128) choose = choose.multiply(BigInteger.valueOf(128 - count))
                    .divide(BigInteger.valueOf(count + 1));
        }
        assertEquals(expected, actual);
        assertEquals(Mass.ONE, FiniteSamplingOracle.total(actual));
    }

    @Test
    void all129AggregatedAttributeAndPurityOutcomesKeepTheirJointMass() {
        for (boolean typeOnly : new boolean[]{false, true}) {
            for (boolean fullPurity : new boolean[]{false, true}) {
                var actual = FiniteSamplingOracle.enumerate(decisions -> {
                    var sampler = new GeneSampler();
                    var random = random(decisions, List.of(new FiniteSamplingOracle.Branch(0.5, Mass.ONE)));
                    long hits = (long) invoke(HITS, sampler, 129L, 1.0F, random);
                    invoke(AGGREGATED, sampler, batch(LOW, 129, null, 0), hits, random, typeOnly, fullPurity);
                    return counts(sampler);
                });
                var expected = rotationMass(LOW, 129, typeOnly, fullPurity);
                assertEquals(expected, actual);
                assertEquals(Mass.ONE, FiniteSamplingOracle.total(actual));
            }
        }
    }

    @Test
    void mixed129SourcesAndFractionalAllocationHaveCompleteJointMass() {
        var mixed = FiniteSamplingOracle.enumerate(decisions -> {
            var sampler = new GeneSampler();
            invoke(AGGREGATED, sampler, batch(LOW, 64, HIGH, 65), 129L,
                    random(decisions, List.of(new FiniteSamplingOracle.Branch(0.5, Mass.ONE))), false, true);
            return counts(sampler);
        });
        assertEquals(convolve(rotationMass(LOW, 64, false, true),
                rotationMass(HIGH, 65, false, true)), mixed);

        // 条件 H=2，来源权重 1/129、128/129：前来源获得一次的概率恰为 2/129。
        var offsets = List.of(new FiniteSamplingOracle.Branch(0.25, Mass.of(127, 129)),
                new FiniteSamplingOracle.Branch(0.999, Mass.of(2, 129)));
        var conditional = FiniteSamplingOracle.enumerate(decisions -> {
            var sampler = new GeneSampler();
            invoke(AGGREGATED, sampler, batch(LOW, 1, HIGH, 128), 2L,
                    random(decisions, offsets), false, false);
            return counts(sampler);
        });
        Map<Map<String, Long>, Mass> expected = new LinkedHashMap<>();
        rotationMass(HIGH, 2, false, false).forEach((value, probability) ->
                FiniteSamplingOracle.add(expected, value, probability.multiply(Mass.of(127, 129))));
        convolve(rotationMass(LOW, 1, false, false), rotationMass(HIGH, 1, false, false))
                .forEach((value, probability) ->
                        FiniteSamplingOracle.add(expected, value, probability.multiply(Mass.of(2, 129))));
        assertEquals(expected, conditional);
    }

    @Test
    void productionEntrySelectsExactAt128AndAggregateAt129() {
        for (int events : new int[]{128, 129}) {
            int[] calls = new int[2];
            RandomSource random = (RandomSource) Proxy.newProxyInstance(RandomSource.class.getClassLoader(),
                    new Class<?>[]{RandomSource.class}, (proxy, called, args) -> {
                        if (called.getName().equals("nextFloat")) {
                            calls[0]++;
                            return 0.75F;
                        }
                        if (called.getName().equals("nextDouble")) {
                            calls[1]++;
                            return calls[1] == 1 ? 1.0E-12 : 0.5;
                        }
                        throw new AssertionError("Unexpected decision " + called);
                    });
            invoke(ENTRY, new GeneSampler(), new ArrayList<>(), batch(LOW, events, null, 0),
                    ResourceLocation.parse("example:finite_oracle"), 0.01F, random, true, true);
            assertEquals(events == 128 ? 128 : 0, calls[0]);
            assertEquals(events == 129 ? 2 : 0, calls[1]);
        }
    }

    private static Map<Map<String, Long>, Mass> singleEvent(GeneSampleProfile profile,
            boolean typeOnly, boolean fullPurity) {
        Map<Map<String, Long>, Mass> result = new LinkedHashMap<>();
        result.put(Map.of(), Mass.of(8_388_607, 16_777_216));
        int attributes = typeOnly ? 1 : 6;
        int purities = fullPurity ? 1 : 4;
        for (int attribute = 0; attribute < attributes; attribute++) {
            for (int purity = 0; purity < purities; purity++) {
                FiniteSamplingOracle.add(result, Map.of(key(profile, attribute, fullPurity ? 3 : purity), 1L),
                        Mass.of(8_388_609, 16_777_216L * attributes * purities));
            }
        }
        return result;
    }

    /** 合同 oracle：把每个 token 放到均匀旋转的圆环，独立于实现的商/余数分配。 */
    private static Map<Map<String, Long>, Mass> rotationMass(GeneSampleProfile profile, int total,
            boolean typeOnly, boolean fullPurity) {
        Map<Map<String, Long>, Mass> result = new LinkedHashMap<>();
        int attributes = typeOnly ? 1 : 6;
        for (int rotation = 0; rotation < attributes; rotation++) {
            int[] perAttribute = new int[6];
            for (int token = 0; token < total; token++) perAttribute[(rotation + token) % attributes]++;
            Map<Map<String, Long>, Mass> partial = Map.of(Map.of(), Mass.of(1, attributes));
            for (int attribute = 0; attribute < attributes; attribute++) {
                Map<Map<String, Long>, Mass> purityMass = new LinkedHashMap<>();
                int purityRotations = fullPurity ? 1 : 4;
                for (int purityRotation = 0; purityRotation < purityRotations; purityRotation++) {
                    Map<String, Long> tokens = new LinkedHashMap<>();
                    for (int token = 0; token < perAttribute[attribute]; token++) {
                        int purity = fullPurity ? 3 : (purityRotation + token) % 4;
                        tokens.merge(key(profile, attribute, purity), 1L, Long::sum);
                    }
                    FiniteSamplingOracle.add(purityMass, Map.copyOf(tokens), Mass.of(1, purityRotations));
                }
                partial = convolve(partial, purityMass);
            }
            partial.forEach((value, probability) -> FiniteSamplingOracle.add(result, value, probability));
        }
        return result;
    }

    private static Map<Map<String, Long>, Mass> convolve(Map<Map<String, Long>, Mass> left,
            Map<Map<String, Long>, Mass> right) {
        Map<Map<String, Long>, Mass> result = new LinkedHashMap<>();
        left.forEach((a, pa) -> right.forEach((b, pb) -> {
            Map<String, Long> sum = new LinkedHashMap<>(a);
            b.forEach((key, count) -> sum.merge(key, count, Long::sum));
            FiniteSamplingOracle.add(result, Map.copyOf(sum), pa.multiply(pb));
        }));
        return result;
    }

    private static String key(GeneSampleProfile profile, int attribute, int purity) {
        GeneAttribute type = GeneAttribute.values()[attribute];
        String value = type == GeneAttribute.TYPE ? "TYPE" : switch (type) {
            case PRODUCTIVITY -> profile.productivity().name();
            case ENDURANCE -> profile.endurance().name();
            case TEMPER -> profile.temper().name();
            case BEHAVIOR -> profile.behavior().name();
            case WEATHER_TOLERANCE -> profile.weatherTolerance().name();
            default -> throw new AssertionError(type);
        };
        return type.name() + ":" + value + ":" + purity;
    }

    private static Map<String, Long> counts(GeneSampler sampler) {
        try {
            long[] counts = (long[]) COUNTS.get(sampler);
            Map<String, Long> result = new LinkedHashMap<>();
            int stride = GeneValue.values().length * 4;
            for (int index = 0; index < counts.length; index++) {
                if (counts[index] == 0) continue;
                GeneAttribute attribute = GeneAttribute.values()[index / stride];
                String value = attribute == GeneAttribute.TYPE ? "TYPE"
                        : GeneValue.values()[(index % stride) / 4].name();
                result.put(attribute.name() + ":" + value + ":" + index % 4, counts[index]);
            }
            return Map.copyOf(result);
        } catch (IllegalAccessException failure) {
            throw new AssertionError(failure);
        }
    }

    private static GeneSampler.SampleBatch batch(GeneSampleProfile first, long firstCount,
            GeneSampleProfile second, long secondCount) {
        var batch = new GeneSampler.SampleBatch();
        batch.add(first, firstCount);
        batch.add(second, secondCount);
        return batch;
    }

    private static RandomSource random(FiniteSamplingOracle.Decisions decisions) {
        return random(decisions, null);
    }

    private static RandomSource random(FiniteSamplingOracle.Decisions decisions,
            List<FiniteSamplingOracle.Branch> doubles) {
        return (RandomSource) Proxy.newProxyInstance(RandomSource.class.getClassLoader(),
                new Class<?>[]{RandomSource.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "nextFloat" -> decisions.nextFloat();
                    case "nextInt" -> decisions.nextInt((int) args[0]);
                    case "nextDouble" -> doubles == null ? decisions.nextDouble() : decisions.choose(doubles);
                    default -> throw new AssertionError("Unexpected random method " + method);
                });
    }

    private static Method method(String name, Class<?>... types) {
        try {
            Method result = GeneSampler.class.getDeclaredMethod(name, types);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Field field(String name) {
        try {
            Field result = GeneSampler.class.getDeclaredField(name);
            result.setAccessible(true);
            return result;
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static Object invoke(Method method, GeneSampler sampler, Object... arguments) {
        try {
            return method.invoke(sampler, arguments);
        } catch (InvocationTargetException wrapped) {
            Throwable failure = wrapped.getCause();
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
            throw new AssertionError(failure);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
