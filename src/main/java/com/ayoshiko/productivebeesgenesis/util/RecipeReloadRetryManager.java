package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.resource.ResourceRuntime;
import net.minecraft.core.HolderLookup;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/** 延迟处理配置或 PB 注册较晚的已接收配方；旧管理器与旧代际的任务不再执行。 */
public final class RecipeReloadRetryManager {
    public record PendingRetryContext(RecipeManager recipeManager, HolderLookup.Provider registryAccess,
            Set<ResourceLocation> customRecipes, long acceptedVersion) {
        public PendingRetryContext {
            customRecipes = Set.copyOf(customRecipes);
        }

        PendingRetryContext accepted(long version) {
            return new PendingRetryContext(recipeManager, registryAccess, customRecipes, version);
        }
    }

    private static final AtomicReference<PendingRetryContext> pending = new AtomicReference<>();
    private static final int MAX_RETRY_COUNT = 60;
    private static int retries;

    private RecipeReloadRetryManager() {}

    public static boolean hasPendingRetry() {
        return pending.get() != null;
    }

    public static void scheduleRetry(RecipeManager manager, HolderLookup.Provider registries) {
        scheduleRetry(manager, registries, Set.of());
    }

    public static void scheduleRetry(RecipeManager manager, HolderLookup.Provider registries, Set<ResourceLocation> custom) {
        retries = 0;
        rescheduleRetry(manager, registries, custom);
    }

    public static void rescheduleRetry(RecipeManager manager, HolderLookup.Provider registries) {
        rescheduleRetry(manager, registries, Set.of());
    }

    public static void rescheduleRetry(RecipeManager manager, HolderLookup.Provider registries, Set<ResourceLocation> custom) {
        long version = CentrifugeRecipeIndex.isCurrent(manager) ? ProductiveBeesGenesis.RECIPE_VERSION.get() : -1;
        pending.set(new PendingRetryContext(manager, registries, custom, version));
    }

    /** 只有服务器接收的管理器才能取得有效重试代际。 */
    public static void accepted(RecipeManager manager, long version) {
        var current = pending.get();
        if (current == null) return;
        if (current.recipeManager() == manager) pending.compareAndSet(current, current.accepted(version));
        else pending.compareAndSet(current, null);
    }

    public static void clearPendingRetryContext() {
        pending.set(null);
        retries = 0;
    }

    public static void onServerTick(MinecraftServer server, Consumer<PendingRetryContext> reloader) {
        var current = pending.get();
        if (current == null || ResourceRuntime.reloadInProgress(server)) return;
        if (current.recipeManager() != server.getRecipeManager()
                || current.acceptedVersion() != ProductiveBeesGenesis.RECIPE_VERSION.get()) {
            pending.compareAndSet(current, null);
            return;
        }
        if (++retries > MAX_RETRY_COUNT) {
            ProductiveBeesGenesis.LOGGER.error("配方重载重试超过上限 {}，保留已接收的当前配方", MAX_RETRY_COUNT);
            pending.compareAndSet(current, null);
            retries = 0;
            return;
        }
        if (!ModConfig.areServerSpecsLoaded()) return;
        if (!pending.compareAndSet(current, null)) return;
        try {
            reloader.accept(current);
        } catch (RuntimeException failure) {
            ProductiveBeesGenesis.LOGGER.error("当前代际的延迟配方处理失败", failure);
        } finally {
            if (pending.get() == null) retries = 0;
        }
    }
}
