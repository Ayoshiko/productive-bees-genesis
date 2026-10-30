package com.ayoshiko.productivebeesgenesis.network;

import net.minecraft.network.FriendlyByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.*;
import java.util.function.*;

/** Bounded collection serialization for the Forge 1.20.1 protocol. */
final class PacketIO {
    private PacketIO() {}
    static <T> List<T> readList(FriendlyByteBuf buf, Supplier<T> reader, int maximum) {
        int count = buf.readVarInt();
        if (count < 0 || count > maximum) throw new DecoderException("Invalid collection size: " + count);
        List<T> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) result.add(reader.get());
        return List.copyOf(result);
    }
    static <T> void writeList(FriendlyByteBuf buf, List<T> values, Consumer<T> writer, int maximum) {
        if (values.size() > maximum) throw new EncoderException("Collection exceeds " + maximum);
        buf.writeVarInt(values.size());
        values.forEach(writer);
    }
    static Map<String, Boolean> readFlags(FriendlyByteBuf buf) {
        int count = buf.readVarInt();
        if (count < 0 || count > 1024) throw new DecoderException("Invalid flag count");
        Map<String, Boolean> flags = new HashMap<>();
        for (int i = 0; i < count; i++) flags.put(buf.readUtf(64), buf.readBoolean());
        return Map.copyOf(flags);
    }
    static void writeFlags(FriendlyByteBuf buf, Map<String, Boolean> flags) {
        if (flags.size() > 1024) throw new EncoderException("Too many flags");
        buf.writeVarInt(flags.size());
        flags.forEach((name, value) -> { buf.writeUtf(name, 64); buf.writeBoolean(value); });
    }
}
