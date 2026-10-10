package com.ayoshiko.productivebeesgenesis.apiculture.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.common.TranslatableEnum;

/** 有界的物品 ID 筛选快照；不持有堆叠、注册表或世界，组件仍由原资产交接精确核对。 */
public record WirelessItemFilter(Mode mode, List<String> items) {
    public enum Mode implements TranslatableEnum {
        ALL, ALLOW, DENY;
        @Override public Component getTranslatedName() {
            return Component.translatable("productivebeesgenesis.configuration.wirelessFilter." + name().toLowerCase(Locale.ROOT));
        }
    }
    public static final int MAX_ITEMS = 16, MAX_ID_LENGTH = 128;
    // 合法 ID 仅 ASCII，每个 UTF 字符串最多两个长度字节。
    public static final int MAX_WIRE_BYTES = 2 + MAX_ITEMS * (MAX_ID_LENGTH + 2);
    public static final WirelessItemFilter ALL = new WirelessItemFilter(Mode.ALL, List.of());

    public WirelessItemFilter {
        if (mode == null || items == null || items.size() > MAX_ITEMS || !items.stream().allMatch(WirelessItemFilter::validId))
            throw new IllegalArgumentException("Invalid wireless item filter");
        items = mode == Mode.ALL ? List.of() : items.stream().distinct().sorted().toList();
    }
    public static boolean validId(Object value) {
        if (!(value instanceof String id) || id.length() > MAX_ID_LENGTH || id.indexOf(':') <= 0 || id.endsWith(":")) return false;
        var parsed = ResourceLocation.tryParse(id);
        return parsed != null && parsed.toString().equals(id);
    }
    public boolean allows(ResourceLocation id) {
        return id != null && (mode == Mode.ALL || items.contains(id.toString()) == (mode == Mode.ALLOW));
    }
    public boolean allows(ItemStack stack) {
        return !stack.isEmpty() && allows(BuiltInRegistries.ITEM.getKey(stack.getItem()));
    }
    public boolean rejectsAll() { return mode == Mode.ALLOW && items.isEmpty(); }
    public void write(FriendlyByteBuf buffer) {
        buffer.writeByte(mode.ordinal()); buffer.writeByte(items.size());
        for (var id : items) buffer.writeUtf(id, MAX_ID_LENGTH);
    }
    public static WirelessItemFilter read(FriendlyByteBuf buffer) {
        int mode = buffer.readUnsignedByte(), count = buffer.readUnsignedByte();
        if (mode >= Mode.values().length || count > MAX_ITEMS) throw new IllegalArgumentException("Invalid wireless filter header");
        var items = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) items.add(buffer.readUtf(MAX_ID_LENGTH));
        return new WirelessItemFilter(Mode.values()[mode], items);
    }
}
