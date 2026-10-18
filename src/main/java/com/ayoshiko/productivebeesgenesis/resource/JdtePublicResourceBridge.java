package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.*;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.mek.ae2.JdteNativeResourceRecipes;
import cy.jdkdigital.productivebees.ProductiveBees;
import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import cy.jdkdigital.productivebees.util.GeneAttribute;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.animal.Bee;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.ItemStackHandler;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiPredicate;
import java.util.function.Supplier;

/** v3 仅提供原生产周期的配方产物；旧 v1／v2 保留独立兼容入口。 */
final class JdtePublicResourceBridge {
    private static final ResourceLocation BIO_FACTORY = ResourceLocation.parse("jdte:bio_factory");
    private static BlockEntityType<?> bioFactoryType;
    private static boolean installed;
    private static int apiVersion;
    private static Constructor<?> nativeOutputConstructor;
    private static final ClassValue<Map<String, Method>> METHODS = new ClassValue<>() {
        @Override protected Map<String, Method> computeValue(Class<?> type) {
            var methods = new HashMap<String, Method>();
            for (var method : type.getMethods()) methods.putIfAbsent(method.getName() + "/" + method.getParameterCount(), method);
            return Map.copyOf(methods);
        }
    };
    private JdtePublicResourceBridge() {}

    @SuppressWarnings("unchecked")
    static void install() {
        if (installed) return;
        try {
            bioFactoryType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BIO_FACTORY);
            var event = Class.forName("com.jdte.api.BioFactoryResourceCycleEvent").asSubclass(Event.class);
            apiVersion = event.getField("API_VERSION").getInt(null);
            if (apiVersion == 3) {
                event.getMethod("setOutputs", List.class);
                event.getMethod("reject");
                nativeOutputConstructor = Class.forName(event.getName() + "$ResourceOutput").getConstructor(
                        ResourceLocation.class, ResourceLocation.class, CompoundTag.class, long.class);
            } else {
                event.getMethod("commit", long.class, Runnable.class);
                event.getMethod("claim");
            }
            if (apiVersion >= 2) {
                BiPredicate<BlockEntity, Bee> matcher = JdtePublicResourceBridge::matchesExtendedRecipe;
                event.getMethod("registerBeeMatcher", BiPredicate.class).invoke(null, matcher);
            }
            if (apiVersion == 3) {
                NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class<Event>) event, JdtePublicResourceBridge::nativeCycle);
            } else {
                NeoForge.EVENT_BUS.addListener(EventPriority.NORMAL, false, (Class<Event>) event, JdtePublicResourceBridge::cycle);
            }
            installed = true;
        } catch (ReflectiveOperationException | LinkageError failure) {
            ProductiveBeesGenesis.LOGGER.error("JDTE public resource API unavailable", failure);
        }
    }

    static void register(RegisterResourceHostsEvent event) {
        install();
        if (apiVersion == 3) return;
        var type = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(BIO_FACTORY);
        if (type == null) return;
        event.register(type, new ResourceHostAdapter() {
            @Override public boolean ready(BlockEntity host) { return installed; }
            @Override public boolean canAccess(ServerPlayer player, BlockEntity host) { return (boolean) call(host, "canAccessResourceCycle", player); }
            @Override public boolean hasLiveWork(BlockEntity host) { return (boolean) call(host, "hasLiveResourceWork"); }
            @Override public boolean canResolveProduction(BlockEntity host) { return (boolean) call(host, "canResolveResourceCycle"); }
            @Override public void resolveProduction(BlockEntity host, CompoundTag context, boolean completed) {
                var adapter = context.getCompound("adapter_context");
                if (adapter.contains("cycle_sequence", 4)) call(host, "resolveResourceCycle", adapter.getLong("cycle_sequence"));
            }
        });
    }

    private static boolean matchesExtendedRecipe(BlockEntity host, Bee bee) {
        return host.getType() == bioFactoryType
                && host.getLevel() instanceof ServerLevel level && level.getServer().isSameThread()
                && bee instanceof ConfigurableBee configurable
                && !ResourceRuntime.recipes(level.getServer()).forBee(configurable.getBeeType()).isEmpty();
    }

    private static void nativeCycle(Event event) {
        var host = (BlockEntity) call(event, "host");
        var candidate = (Bee) call(event, "bee");
        if (!matchesExtendedRecipe(host, candidate)) return;
        var level = (ServerLevel) host.getLevel();
        var bee = (ConfigurableBee) candidate;
        var spec = ResourceRuntime.pbCatalog(level).get(bee.getBeeType());
        if (spec == null) return;
        if (!spec.validForJdte() || !Ae2IntegrationLoader.isAe2Loaded()) {
            call(event, "reject");
            return;
        }
        try {
            double rolls = (double) call(event, "rolls");
            call(event, "setOutputs", JdteNativeResourceRecipes.outputs(level, bee, rolls, nativeOutputConstructor));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            call(event, "reject");
            com.ayoshiko.productivebeesgenesis.util.LogThrottle.warnWithCooldown("jdte_native_resource_recipe", 5000,
                    "JDTE native resource recipe unavailable at {}", host.getBlockPos(), failure);
        }
    }

    private static void cycle(Event event) {
        var host = (BlockEntity) call(event, "host");
        var candidate = (Bee) call(event, "bee");
        if (!matchesExtendedRecipe(host, candidate)) return;
        var level = (ServerLevel) host.getLevel();
        var bee = (ConfigurableBee) candidate;
        var spec = ResourceRuntime.pbCatalog(level).get(bee.getBeeType());
        if (spec == null) return;
        call(event, "claim");
        var state = ResourceHiveHost.existing(host);
        if (state == null || state.reference() == null) return;
        if (!spec.validForJdte()) { state.status(spec.containerFlower() ? "unsupported_bee" : spec.problem()); return; }
        try {
            var account = state.account(host);
            if (!account.canExtract(state.reference())) return;
            double rolls = (double) call(event, "rolls");
            var attributes = bee.getData(ProductiveBees.ATTRIBUTE_HANDLER).getAttributeValue(GeneAttribute.PRODUCTIVITY);
            int gene = attributes == null ? 0 : attributes.getValue();
            var index = ResourceProductionApi.snapshot(level.getServer());
            var plan = ResourceProductionApi.plan(index, bee.getBeeType(), rolls, gene, 0);
            long cycles = (long) call(event, "affordableCycles");
            for (var output : plan.maximumExact(1).entrySet()) {
                if (ResourceTypes.EMC.equals(output.getKey())) continue;
                long room = Math.max(0, state.capacity() - account.available().getOrDefault(output.getKey(), 0L));
                cycles = Math.min(cycles, java.math.BigInteger.valueOf(room).divide(output.getValue()).min(java.math.BigInteger.valueOf(Long.MAX_VALUE)).longValueExact());
            }
            boolean blocks = (boolean) call(event, "blocks");
            var inventory = (ItemStackHandler) call(host, "getMachineHandler");
            Supplier<int[]> slots = () -> (int[]) call(host, "resourceOutputSlots");
            if (spec.mixed()) {
                cycles = Math.min(cycles, (long) (1_000_000 / Math.ceil(rolls)));
                long low = 0, high = cycles;
                while (low < high) {
                    long middle = (low + high + 1) >>> 1;
                    if (fits(spec, rolls, gene, blocks, middle, inventory, slots)) low = middle; else high = middle - 1;
                }
                cycles = low;
            }
            if (cycles <= 0) return;
            long acceptedCycles = cycles;
            var context = (CompoundTag) call(event, "context");
            var local = new PbLocalOutputPlan[1];
            var mixed = spec.mixed() ? new PbMixedProduction(spec.nativeItems(), rolls * cycles, gene, blocks, false) : null;
            var reserved = ResourceHostApi.reservePrepared(host, bee.getBeeType(), cycles, rolls, gene, sample -> {
                if (mixed != null) {
                    local[0] = PbLocalOutputPlan.prepare(inventory, slots, mixed.sample(sample.rolls(), level.random.nextLong()));
                    if (local[0] == null) return null;
                    context.put("local_output", local[0].context(level.registryAccess()));
                }
                return context;
            });
            if (reserved.isEmpty()) return;
            try (var transaction = reserved.get()) {
                transaction.executeExact(() -> call(event, "commit", acceptedCycles, (Runnable) () -> {
                    if (local[0] != null) local[0].commit(() -> {});
                }));
            }
        } catch (RuntimeException | LinkageError failure) {
            state.status("production_uncertain");
            com.ayoshiko.productivebeesgenesis.util.LogThrottle.warnWithCooldown("jdte_resource_public", 5000,
                    "JDTE public resource cycle paused at {}", host.getBlockPos(), failure);
        }
    }

    private static boolean fits(PbResourceCatalog.Spec spec, double rolls, int gene, boolean blocks, long cycles,
            ItemStackHandler inventory, Supplier<int[]> slots) {
        try { return PbLocalOutputPlan.prepare(inventory, slots,
                new PbMixedProduction(spec.nativeItems(), rolls * cycles, gene, blocks, false).maximum()) != null; }
        catch (ArithmeticException invalid) { return false; }
    }

    private static Object call(Object receiver, String name, Object... arguments) {
        try {
            var method = METHODS.get(receiver.getClass()).get(name + "/" + arguments.length);
            if (method == null) throw new IllegalStateException("Missing public JDTE method " + name);
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error failure) throw failure;
            throw new IllegalStateException("JDTE public cycle failed", error.getCause());
        } catch (ReflectiveOperationException error) { throw new IllegalStateException("JDTE public API mismatch", error); }
    }
}
