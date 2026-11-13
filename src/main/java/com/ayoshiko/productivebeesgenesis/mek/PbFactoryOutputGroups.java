package com.ayoshiko.productivebeesgenesis.mek;

import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** 可复用的本轮交付视图；模板引用只存活到本轮收尾，数量资产仍属于原车道。 */
final class PbFactoryOutputGroups<T> {
    private static final int INITIAL_CAPACITY = 4;
    private static final int MIN_RETAINED_SOURCES = 16;
    private static final int MAX_RETAINED_SOURCES = 4096;
    private static final int RETAINED_SOURCES_PER_LANE = 8;
    private static final int LINEAR_COMPONENT_VARIANTS = 4;

    private final Function<T, Object> type;
    private final BiPredicate<T, T> same;
    private final ToIntFunction<T> hash;
    private final int retainedLimit;
    private IdentityHashMap<T, Group<T>> references = new IdentityHashMap<>(INITIAL_CAPACITY);
    private IdentityHashMap<Object, Group<T>> types = new IdentityHashMap<>(INITIAL_CAPACITY);
    private HashMap<Integer, Group<T>> hashedVariants = new HashMap<>();
    private ArrayList<Group<T>> groups = new ArrayList<>(INITIAL_CAPACITY);
    private Object[] sourceTemplates = new Object[INITIAL_CAPACITY];
    private int[] sourceLanes = new int[INITIAL_CAPACITY];
    private long[] sourceAmounts = new long[INITIAL_CAPACITY];
    private int[] nextSources = new int[INITIAL_CAPACITY];
    private Group<T> firstKey;
    private Group<T> lastKey;
    private int groupCount;
    private int sourceCount;

    private PbFactoryOutputGroups(int lanes, Function<T, Object> type, BiPredicate<T, T> same,
            ToIntFunction<T> hash) {
        this.type = type;
        this.same = same;
        this.hash = hash;
        retainedLimit = (int) Math.min(MAX_RETAINED_SOURCES,
                Math.max(MIN_RETAINED_SOURCES, (long) lanes * RETAINED_SOURCES_PER_LANE));
    }

    static PbFactoryOutputGroups<ItemStack> items(int lanes) {
        return new PbFactoryOutputGroups<>(lanes, ItemStack::getItem,
                (first, second) -> first.getItem() == second.getItem()
                        && first.getComponents().equals(second.getComponents()),
                ItemStack::hashItemAndComponents);
    }

    static PbFactoryOutputGroups<FluidStack> fluids(int lanes) {
        // 每车道至多一种流体；保持原来的精确组件比较，不为少量流体增加哈希工作。
        return new PbFactoryOutputGroups<>(lanes, FluidStack::getFluid,
                FluidStack::isSameFluidSameComponents, null);
    }

    void add(int lane, T template, long amount) {
        if (amount <= 0) return;
        // collect 在服务器线程封闭执行，外部交付尚未开始；引用索引不跨批保留。
        Group<T> key = references.get(template);
        if (key == null) {
            Object baseType = type.apply(template);
            Group<T> firstVariant = types.get(baseType);
            key = find(firstVariant, template);
            if (key == null) {
                key = newGroup(template);
                key.key = key;
                key.lastPart = key;
                if (lastKey == null) firstKey = key;
                else lastKey.nextKey = key;
                lastKey = key;
                if (firstVariant == null) {
                    types.put(baseType, key);
                    key.variantCount = 1;
                } else {
                    key.nextVariant = firstVariant.nextVariant;
                    firstVariant.nextVariant = key;
                    firstVariant.variantCount++;
                    if (firstVariant.indexed) {
                        index(key);
                    } else if (hash != null && firstVariant.variantCount > LINEAR_COMPONENT_VARIANTS) {
                        for (Group<T> variant = firstVariant; variant != null; variant = variant.nextVariant) {
                            index(variant);
                        }
                        firstVariant.indexed = true;
                    }
                }
            }
            references.put(template, key);
        }
        Group<T> part = key.lastPart;
        if (amount > Long.MAX_VALUE - part.amount) {
            Group<T> overflow = newGroup(template);
            overflow.key = key;
            part.nextPart = overflow;
            key.lastPart = overflow;
            part = overflow;
        }
        ensureSourceCapacity(sourceCount + 1);
        int source = sourceCount++;
        sourceTemplates[source] = template;
        sourceLanes[source] = lane;
        sourceAmounts[source] = amount;
        nextSources[source] = -1;
        if (part.lastSource < 0) part.firstSource = source;
        else nextSources[part.lastSource] = source;
        part.lastSource = source;
        part.amount += amount;
    }

    private Group<T> find(Group<T> firstVariant, T template) {
        if (firstVariant == null) return null;
        if (firstVariant.indexed) {
            for (Group<T> candidate = hashedVariants.get(hash.applyAsInt(template));
                    candidate != null; candidate = candidate.nextHash) {
                if (same.test(candidate.template, template)) return candidate;
            }
        } else {
            for (Group<T> candidate = firstVariant; candidate != null; candidate = candidate.nextVariant) {
                if (same.test(candidate.template, template)) return candidate;
            }
        }
        return null;
    }

    private void index(Group<T> group) {
        int identityHash = hash.applyAsInt(group.template);
        group.nextHash = hashedVariants.put(identityHash, group);
    }

    private Group<T> newGroup(T template) {
        if (groupCount == groups.size()) groups.add(new Group<>());
        Group<T> group = groups.get(groupCount++);
        group.template = template;
        return group;
    }

    private void ensureSourceCapacity(int required) {
        if (required <= sourceTemplates.length) return;
        int capacity = (int) Math.max(required, Math.min(Integer.MAX_VALUE - 8L,
                (long) sourceTemplates.length * 2));
        sourceTemplates = Arrays.copyOf(sourceTemplates, capacity);
        sourceLanes = Arrays.copyOf(sourceLanes, capacity);
        sourceAmounts = Arrays.copyOf(sourceAmounts, capacity);
        nextSources = Arrays.copyOf(nextSources, capacity);
    }

    Group<T> firstKey() {
        return firstKey;
    }

    int groupCount() {
        return groupCount;
    }

    Group<T> group(int index) {
        return groups.get(index);
    }

    int nextSource(int source) {
        return nextSources[source];
    }

    int sourceLane(int source) {
        return sourceLanes[source];
    }

    @SuppressWarnings("unchecked")
    T sourceTemplate(int source) {
        return (T) sourceTemplates[source];
    }

    long sourceAmount(int source) {
        return sourceAmounts[source];
    }

    void debitSource(int source, long amount) {
        sourceAmounts[source] -= amount;
    }

    void clear(boolean release) {
        for (int index = 0; index < groupCount; index++) groups.get(index).clear();
        Arrays.fill(sourceTemplates, 0, sourceCount, null);
        firstKey = null;
        lastKey = null;
        groupCount = 0;
        sourceCount = 0;
        boolean largeReferences = references.size() > retainedLimit;
        boolean largeTypes = types.size() > retainedLimit;
        boolean largeHashes = hashedVariants.size() > retainedLimit;
        if (!references.isEmpty()) references.clear();
        if (!types.isEmpty()) types.clear();
        if (!hashedVariants.isEmpty()) hashedVariants.clear();
        if (release) {
            references = new IdentityHashMap<>(INITIAL_CAPACITY);
            types = new IdentityHashMap<>(INITIAL_CAPACITY);
            hashedVariants = new HashMap<>();
            groups = new ArrayList<>(INITIAL_CAPACITY);
            resizeSources(INITIAL_CAPACITY);
            return;
        }
        // 单次大配方不能永久扩大每台机器的保留量；本次资产与交付量从不受此上限截断。
        if (largeReferences) references = new IdentityHashMap<>(retainedLimit);
        if (largeTypes) types = new IdentityHashMap<>(retainedLimit);
        if (largeHashes) hashedVariants = new HashMap<>();
        if (groups.size() > retainedLimit) {
            ArrayList<Group<T>> retained = new ArrayList<>(retainedLimit);
            for (int index = 0; index < retainedLimit; index++) retained.add(groups.get(index));
            groups = retained;
        }
        if (sourceTemplates.length > retainedLimit) resizeSources(retainedLimit);
    }

    private void resizeSources(int capacity) {
        sourceTemplates = new Object[capacity];
        sourceLanes = new int[capacity];
        sourceAmounts = new long[capacity];
        nextSources = new int[capacity];
    }

    static final class Group<T> {
        T template;
        long amount;
        int firstSource = -1;
        int lastSource = -1;
        Group<T> key;
        Group<T> nextKey;
        Group<T> lastPart;
        Group<T> nextPart;
        Group<T> nextVariant;
        Group<T> nextHash;
        int variantCount;
        boolean indexed;
        boolean rejected;

        private void clear() {
            template = null;
            amount = 0;
            firstSource = -1;
            lastSource = -1;
            key = null;
            nextKey = null;
            lastPart = null;
            nextPart = null;
            nextVariant = null;
            nextHash = null;
            variantCount = 0;
            indexed = false;
            rejected = false;
        }
    }
}
