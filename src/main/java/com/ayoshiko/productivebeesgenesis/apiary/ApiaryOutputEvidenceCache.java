package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/** 只读审计编码；复用组件模板，真实数量与顺序逐批保留，缓存不持有可投递资产。 */
final class ApiaryOutputEvidenceCache {
    private static final int MAX_ENTRIES = 16;
    private static final int MAX_TEMPLATES = 64;
    private static final int MAX_STACKS = 32;
    private static final int MAX_ENTRY_CHARACTERS = 8192;
    private static final int MAX_RETAINED_CHARACTERS = 32768;

    private record Entry(List<ItemStack> items, String encoded) {}
    private record Template(ItemStack item, String prefix, String suffix) {
        int characters() {
            return prefix.length() + suffix.length();
        }
    }

    private final List<Entry> entries = new ArrayList<>();
    private final List<Template> templates = new ArrayList<>();
    private HolderLookup.Provider provider;
    private long revision = Long.MIN_VALUE;
    private int retainedCharacters;

    void clear() {
        entries.clear();
        templates.clear();
        retainedCharacters = 0;
        provider = null;
        revision = Long.MIN_VALUE;
    }

    String encode(List<ItemStack> items, HolderLookup.Provider currentProvider) {
        long currentRevision = ProductiveBeesGenesis.RECIPE_VERSION.get();
        if (provider != currentProvider || revision != currentRevision) {
            clear();
            provider = currentProvider;
            revision = currentRevision;
        }
        if (items.size() <= MAX_STACKS) {
            for (int index = entries.size() - 1; index >= 0; index--) {
                Entry entry = entries.get(index);
                if (matches(entry.items(), items)) return entry.encoded();
            }
        }
        StringBuilder saved = new StringBuilder("[");
        for (ItemStack stack : items) {
            if (stack.isEmpty()) continue;
            Template template = template(stack, currentProvider);
            if (saved.length() > 1) saved.append(',');
            saved.append(template.prefix()).append(stack.getCount()).append(template.suffix());
        }
        String encoded = saved.append(']').toString();
        if (items.size() <= MAX_STACKS && encoded.length() <= MAX_ENTRY_CHARACTERS) {
            List<ItemStack> snapshot = new ArrayList<>(items.size());
            for (ItemStack stack : items) snapshot.add(stack.copy());
            if (entries.size() >= MAX_ENTRIES) {
                retainedCharacters -= entries.removeFirst().encoded().length();
            }
            makeRoom(encoded.length());
            entries.add(new Entry(List.copyOf(snapshot), encoded));
            retainedCharacters += encoded.length();
        }
        return encoded;
    }

    private Template template(ItemStack stack, HolderLookup.Provider currentProvider) {
        for (int index = templates.size() - 1; index >= 0; index--) {
            Template template = templates.get(index);
            if (sameIdentity(template.item(), stack)) return template;
        }
        ItemStack snapshot = stack.copyWithCount(1);
        CompoundTag item = (CompoundTag) snapshot.save(currentProvider);
        // 标准 SNBT 按字段名排序。只拆顶层 generated_count 的位置，嵌套同名字段仍由原序列化器编码。
        CompoundTag beforeCount = new CompoundTag();
        CompoundTag afterCount = new CompoundTag();
        for (String key : item.getAllKeys()) {
            if (key.compareTo("generated_count") < 0) beforeCount.put(key, item.get(key));
            else afterCount.put(key, item.get(key));
        }
        String before = beforeCount.toString();
        String after = afterCount.toString();
        String prefix = before.substring(0, before.length() - 1)
                + (beforeCount.isEmpty() ? "" : ",") + "generated_count:";
        String suffix = (afterCount.isEmpty() ? "" : ",") + after.substring(1);
        Template template = new Template(snapshot, prefix, suffix);
        if (template.characters() <= MAX_ENTRY_CHARACTERS) {
            if (templates.size() >= MAX_TEMPLATES) {
                retainedCharacters -= templates.removeFirst().characters();
            }
            makeRoom(template.characters());
            templates.add(template);
            retainedCharacters += template.characters();
        }
        return template;
    }

    private void makeRoom(int characters) {
        while (retainedCharacters + characters > MAX_RETAINED_CHARACTERS) {
            if (!entries.isEmpty()) retainedCharacters -= entries.removeFirst().encoded().length();
            else retainedCharacters -= templates.removeFirst().characters();
        }
    }

    private static boolean matches(List<ItemStack> previous, List<ItemStack> current) {
        if (previous.size() != current.size()) return false;
        for (int index = 0; index < current.size(); index++) {
            ItemStack first = previous.get(index);
            ItemStack second = current.get(index);
            if (first.getCount() != second.getCount() || !sameIdentity(first, second)) return false;
        }
        return true;
    }

    private static boolean sameIdentity(ItemStack first, ItemStack second) {
        return first.getItem() == second.getItem() && first.getComponents().equals(second.getComponents());
    }
}
