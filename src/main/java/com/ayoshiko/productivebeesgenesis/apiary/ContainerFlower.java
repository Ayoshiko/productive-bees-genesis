package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceProductionRecipe;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import mekanism.api.MekanismAPI;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.capabilities.Capabilities;

import java.util.Set;

/** 不消费花源：每次查询只访问单件容器副本；数量不能跨物品或槽位拼接。 */
public final class ContainerFlower {
    public static final String JSON_KEY = "flowerResource";
    public static final String NBT_KEY = "productivebeesgenesis:flower_resource";
    private static final int MAX_DEFINITION_BYTES = 4096;
    private static final int MAX_TANKS = 64;
    private final String kind;
    private final ResourceLocation id;
    private final boolean tagged;
    private final long amount;
    private final ResourceKey aeKey;
    private final boolean custom;

    private ContainerFlower(String kind, ResourceLocation id, boolean tagged, long amount, ResourceKey aeKey, boolean custom) {
        this.kind = kind; this.id = id; this.tagged = tagged; this.amount = amount; this.aeKey = aeKey; this.custom = custom;
    }

    private static ContainerFlower invalid(boolean custom) { return new ContainerFlower("invalid", null, false, 0, null, custom); }
    public boolean custom() { return custom; }

    public long minimumAmount() { return amount; }

    public ResourceKey displayResource() {
        if (!syntaxValid() || tagged) return null;
        return switch (kind) {
            case "chemical" -> ResourceKey.chemical(id);
            case "energy" -> com.ayoshiko.productivebeesgenesis.resource.ResourceTypes.FE;
            case "ae_key" -> aeKey;
            default -> null;
        };
    }

    public ResourceLocation displayFluid() { return kind.equals("fluid") && !tagged ? id : null; }

    /** PB 只拷贝自身已知字段；随 PB 原有数据包同步保存有界扩展定义。错误保留为拒绝标记。 */
    public static void attach(ResourceLocation bee, JsonObject json, CompoundTag data) {
        if (!json.has(JSON_KEY)) return;
        String raw = json.get(JSON_KEY).toString();
        boolean conflict = Set.of("flowerTag", "flowerItem", "flowerBlock", "flowerFluid", "inverseFlower", "postPollination")
                .stream().anyMatch(json::has);
        if (json.has("flowerType") && (!json.get("flowerType").isJsonPrimitive()
                || !"blocks".equals(json.get("flowerType").getAsString()))) conflict = true;
        if (conflict || raw.length() > MAX_DEFINITION_BYTES
                || raw.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_DEFINITION_BYTES) raw = "";
        data.putString(NBT_KEY, raw);
        // 阻止其它宿主把 PB 自动补入的 minecraft:flowers 当作本定义的替代花源。
        for (String field : Set.of("flowerTag", "flowerItem", "flowerBlock", "flowerFluid")) data.remove(field);
        data.putString("flowerType", "productivebeesgenesis:container");
        if (!fromData(data).syntaxValid()) ProductiveBeesGenesis.LOGGER.warn("Invalid container flower for {}", bee);
    }

    public static ContainerFlower fromData(CompoundTag data) {
        if (!data.contains(NBT_KEY)) return null;
        return parse(data.getString(NBT_KEY));
    }

    public static ContainerFlower parse(String raw) {
        try {
            if (raw.length() > MAX_DEFINITION_BYTES) return invalid(true);
            var json = JsonParser.parseString(raw).getAsJsonObject();
            String kind = string(json, "kind");
            long amount = json.has("amount") ? ResourceProductionRecipe.AMOUNT_CODEC.parse(JsonOps.INSTANCE, json.get("amount")).getOrThrow() : 1;
            Set<String> allowed = switch (kind) {
                case "fluid", "chemical" -> Set.of("kind", "id", "amount");
                case "energy" -> Set.of("kind", "energy_type", "amount");
                case "ae_key" -> Set.of("kind", "type", "id", "data", "amount");
                default -> Set.of();
            };
            if (allowed.isEmpty() || !allowed.containsAll(json.keySet())) return invalid(true);
            if (kind.equals("energy")) {
                if (!"FE".equals(string(json, "energy_type"))) return invalid(true);
                return new ContainerFlower(kind, null, false, amount, null, true);
            }
            if (kind.equals("ae_key")) {
                var copy = json.deepCopy(); copy.addProperty("amount", Long.toString(amount));
                var output = ResourceProductionRecipe.Output.CODEC.parse(JsonOps.INSTANCE, copy).getOrThrow();
                return new ContainerFlower(kind, ResourceLocation.parse(string(json, "id")), false, amount, output.key(), true);
            }
            String target = string(json, "id");
            boolean tagged = target.startsWith("#");
            return new ContainerFlower(kind, ResourceLocation.parse(tagged ? target.substring(1) : target), tagged, amount, null, true);
        } catch (RuntimeException invalid) { return invalid(true); }
    }

    public static ContainerFlower legacyFluid(String target) {
        try {
            boolean tagged = target.startsWith("#");
            return new ContainerFlower("fluid", ResourceLocation.parse(tagged ? target.substring(1) : target), tagged, 1, null, false);
        } catch (RuntimeException invalid) { return invalid(false); }
    }

    private static String string(JsonObject json, String name) {
        var value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) throw new IllegalArgumentException(name);
        return value.getAsString();
    }

    private boolean syntaxValid() { return !kind.equals("invalid"); }
    public String problem() {
        boolean valid = switch (kind) {
            case "fluid" -> tagged ? BuiltInRegistries.FLUID.getTag(TagKey.create(BuiltInRegistries.FLUID.key(), id))
                    .map(values -> values.stream().anyMatch(h -> h.value() != Fluids.EMPTY)).orElse(false)
                    : BuiltInRegistries.FLUID.containsKey(id) && BuiltInRegistries.FLUID.get(id) != Fluids.EMPTY;
            case "chemical" -> tagged ? MekanismAPI.CHEMICAL_REGISTRY.getTag(TagKey.create(MekanismAPI.CHEMICAL_REGISTRY.key(), id))
                    .map(values -> values.stream().anyMatch(h -> !ResourceLocation.parse("mekanism:empty").equals(MekanismAPI.CHEMICAL_REGISTRY.getKey(h.value())))).orElse(false)
                    : MekanismAPI.CHEMICAL_REGISTRY.containsKey(id) && !id.equals(ResourceLocation.parse("mekanism:empty"));
            case "energy" -> true;
            case "ae_key" -> ModList.get().isLoaded("ae2");
            default -> false;
        };
        return valid ? "" : "invalid_flower";
    }

    public boolean matches(ItemStack original, HolderLookup.Provider registries) {
        if (original.isEmpty() || !syntaxValid()) return false;
        try {
            var stack = original.copyWithCount(1);
            boolean matched = switch (kind) {
                case "fluid" -> fluid(stack);
                case "chemical" -> chemical(stack);
                case "energy" -> energy(stack);
                case "ae_key" -> ModList.get().isLoaded("ae2") && registries != null
                        && com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ContainerFlower.matches(stack, registries, aeKey, amount);
                default -> false;
            };
            if (matched || tagged || registries == null || !ModList.get().isLoaded("ae2")) return matched;
            return com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2ContainerFlower.matchesRequirement(stack, registries, this);
        } catch (RuntimeException | LinkageError failure) {
            LogThrottle.warnWithCooldown("container_flower", 10000, "Container flower query failed for {}", original.getItem(), failure);
            return false;
        }
    }

    private boolean fluid(ItemStack stack) {
        var handler = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (handler != null) {
            int tanks = handler.getTanks();
            if (tanks < 0 || tanks > MAX_TANKS) return false;
            for (int tank = 0; tank < tanks; tank++) {
                var fluid = handler.getFluidInTank(tank);
                if (!fluid.isEmpty() && fluid.getAmount() >= amount && matchesFluid(fluid.getFluid())) return true;
            }
            return false;
        }
        // 保留 PB 的 BucketItem 语义，兼容没有注册物品流体能力的传统桶。
        return amount <= 1000 && stack.getItem() instanceof BucketItem bucket && matchesFluid(bucket.content);
    }

    private boolean matchesFluid(net.minecraft.world.level.material.Fluid fluid) {
        if (fluid == Fluids.EMPTY) return false;
        return tagged ? BuiltInRegistries.FLUID.wrapAsHolder(fluid).is(TagKey.create(BuiltInRegistries.FLUID.key(), id))
                : BuiltInRegistries.FLUID.containsKey(id) && fluid.isSame(BuiltInRegistries.FLUID.get(id));
    }

    private boolean chemical(ItemStack stack) {
        var handler = stack.getCapability(mekanism.common.capabilities.Capabilities.CHEMICAL.item());
        if (handler != null) {
            int tanks = handler.getChemicalTanks();
            if (tanks < 0 || tanks > MAX_TANKS) return false;
            for (int tank = 0; tank < tanks; tank++) {
                var chemical = handler.getChemicalInTank(tank);
                if (!chemical.isEmpty() && chemical.getAmount() >= amount && matchesChemical(chemical.getChemical())) return true;
            }
        }
        // Mekanism 官方气体桶提供流体能力；只接受其真实注册的流体到化学品对应关系。
        var fluids = stack.getCapability(Capabilities.FluidHandler.ITEM);
        if (fluids != null) {
            int tanks = fluids.getTanks();
            if (tanks < 0 || tanks > MAX_TANKS) return false;
            for (int tank = 0; tank < tanks; tank++) {
                var contents = fluids.getFluidInTank(tank);
                if (contents.getAmount() >= amount && matchesChemicalFluid(contents.getFluid())) return true;
            }
        } else if (amount <= 1000 && stack.getItem() instanceof BucketItem bucket) {
            return matchesChemicalFluid(bucket.content);
        }
        return false;
    }

    private boolean matchesChemical(mekanism.api.chemical.Chemical chemical) {
        var chemicalId = MekanismAPI.CHEMICAL_REGISTRY.getKey(chemical);
        return chemicalId != null && !chemicalId.equals(ResourceLocation.parse("mekanism:empty"))
                && (tagged ? MekanismAPI.CHEMICAL_REGISTRY.wrapAsHolder(chemical)
                        .is(TagKey.create(MekanismAPI.CHEMICAL_REGISTRY.key(), id)) : id.equals(chemicalId));
    }

    private boolean matchesChemicalFluid(net.minecraft.world.level.material.Fluid fluid) {
        if (fluid == Fluids.EMPTY) return false;
        var fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        if (fluidId == null || !fluidId.getNamespace().equals("mekanism")) return false;
        boolean registered = mekanism.common.registries.MekanismFluids.FLUIDS.getFluidEntries().stream()
                .anyMatch(entry -> entry.get() == fluid);
        return registered && MekanismAPI.CHEMICAL_REGISTRY.containsKey(fluidId)
                && matchesChemical(MekanismAPI.CHEMICAL_REGISTRY.get(fluidId));
    }

    private boolean energy(ItemStack stack) {
        var strict = stack.getCapability(mekanism.common.capabilities.Capabilities.STRICT_ENERGY.item());
        if (strict != null) {
            var conversion = mekanism.api.energy.IEnergyConversionHelper.INSTANCE.feConversion();
            if (!conversion.isEnabled()) return false;
            int containers = strict.getEnergyContainerCount();
            if (containers < 0 || containers > MAX_TANKS) return false;
            for (int i = 0; i < containers; i++) {
                // 使用 Mekanism 的当前 FE 换算，不能把内部焦耳数直接当成 FE。
                if (conversion.convertTo(strict.getEnergy(i)) >= amount) return true;
            }
            return false;
        }
        var storage = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        return storage != null && storage.getEnergyStored() >= amount;
    }

    public Component description() {
        if (!syntaxValid()) return Component.translatable("gui.productivebeesgenesis.container_flower.invalid");
        String unit = kind.equals("energy") ? "FE" : kind.equals("fluid") || kind.equals("chemical") ? "mB" : "";
        Component name = Component.literal(kind.equals("energy") ? "FE" : (tagged ? "#" : "") + id);
        if (kind.equals("chemical") && !tagged && MekanismAPI.CHEMICAL_REGISTRY.containsKey(id)) {
            name = MekanismAPI.CHEMICAL_REGISTRY.get(id).getTextComponent();
        } else if (kind.equals("fluid") && !tagged && BuiltInRegistries.FLUID.containsKey(id)) {
            name = new net.neoforged.neoforge.fluids.FluidStack(BuiltInRegistries.FLUID.get(id), 1).getHoverName();
        }
        return Component.translatable("gui.productivebeesgenesis.container_flower", name, Long.toString(amount), unit);
    }
}
