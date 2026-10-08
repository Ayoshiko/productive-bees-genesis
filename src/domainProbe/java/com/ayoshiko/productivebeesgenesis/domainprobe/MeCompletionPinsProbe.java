package com.ayoshiko.productivebeesgenesis.domainprobe;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.*;
import appeng.core.network.clientbound.CraftingJobStatusPacket;
import com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingCompletions;
import com.google.gson.JsonObject;
import java.lang.reflect.Proxy;
import java.util.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.*;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 真实 CPU 事件与库存交接；边界注入仅检查显示历史的范围和生命周期。 */
final class MeCompletionPinsProbe {
    private static final AEItemKey IRON = AEItemKey.of(Items.IRON_INGOT);
    private static MinecraftServer server;
    private static int stages;
    private static AEItemKey variant(int index) {
        var stack = new ItemStack(Items.IRON_INGOT); stack.set(DataComponents.CUSTOM_NAME, Component.literal("AAA pin fixture " + index)); return AEItemKey.of(stack);
    }
    static void cancelled(List<ServerPlayer> players) { require(ranks(players.getFirst(), MeCraftingAeFixture.grid).isEmpty(), "Cancelled CPU job was pinned"); }
    static void seed(List<ServerPlayer> players) {
        var owner = players.getFirst(); server = owner.server; var grid = MeCraftingAeFixture.grid;
        require(ranks(owner, grid).keySet().equals(Set.of(IRON)), "Actual FINISHED event did not record its output");
        require(ranks(players.get(1), grid).isEmpty(), "Completion history leaked to guest");
        IGrid other = (IGrid) Proxy.newProxyInstance(IGrid.class.getClassLoader(), new Class<?>[]{IGrid.class}, (p, m, a) -> { throw new UnsupportedOperationException(); });
        require(ranks(owner, other).isEmpty(), "Completion history leaked to another grid identity");
        require(owner.containerMenu.getCarried().isEmpty(), "Pin fixture needs empty cursor");
        for (int i = 0; i < 40; i++) require(grid.getStorageService().getInventory().insert(variant(i), 1, Actionable.MODULATE, IActionSource.empty()) == 1, "Cannot seed off-page output fixture");
        grid.getStorageService().invalidateCache();
    }
    static int advance(com.ayoshiko.productivebeesgenesis.apiculture.core.NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
        var owner = players.getFirst(); stages++;
        long cursor = owner.containerMenu.getCarried().is(Items.IRON_INGOT) ? owner.containerMenu.getCarried().getCount() : 0;
        require(MeCraftingAeFixture.stone() == 60 && MeCraftingAeFixture.iron() + cursor == 2, "Pinned row broke actual material accounting");
        if (cursor > 0) require(ItemStack.isSameItemSameComponents(owner.containerMenu.getCarried(), IRON.toStack()), "Pinned row selected different components");
        if (stage == 625 || stage == 626) require(cursor == 1 && MeCraftingAeFixture.iron() == 1, "Pinned single take or stale rejection failed");
        if (stage == 628) require(cursor == 2 && MeCraftingAeFixture.iron() == 0, "Pinned output was not depleted");
        if (stage == 629) require(cursor == 0 && MeCraftingAeFixture.iron() == 2, "Pinned output return failed");
        if (stage == 630) lifecycle(owner, players.get(1));
        if (stage == 631) {
            AeCraftingCompletions.completed(owner, MeCraftingAeFixture.grid, message(UUID.randomUUID(), IRON, CraftingJobStatusPacket.Status.FINISHED));
            require(!ranks(owner, MeCraftingAeFixture.grid).isEmpty(), "Missing logout cleanup fixture");
            MeCraftingAeFixture.close(); owner.closeContainer(); CraftingProbe.open(core, List.of(owner), false);
        }
        return stage == 632 ? -1 : stage + 1;
    }
    private static void lifecycle(ServerPlayer owner, ServerPlayer guest) {
        var grid = MeCraftingAeFixture.grid; var jobs = histories().get(server).get(owner.getUUID());
        require(jobs.size() == 1, "Native job history duplicated"); UUID actualJob = jobs.keySet().iterator().next();
        AeCraftingCompletions.completed(owner, grid, message(actualJob, variant(0), CraftingJobStatusPacket.Status.FINISHED));
        for (var status : List.of(CraftingJobStatusPacket.Status.STARTED, CraftingJobStatusPacket.Status.CANCELLED))
            AeCraftingCompletions.completed(owner, grid, message(UUID.randomUUID(), variant(0), status));
        require(ranks(owner, grid).keySet().equals(Set.of(IRON)) && jobs.size() == 1, "Duplicate/cancel/start changed completed history");
        for (int i = 0; i < 40; i++) AeCraftingCompletions.completed(owner, grid, message(UUID.randomUUID(), variant(i), CraftingJobStatusPacket.Status.FINISHED));
        var ranked = ranks(owner, grid);
        require(jobs.size() == 32 && ranked.size() == 9 && ranked.get(variant(39)) == 0, "Completion history is not bounded/recent first");
        AeCraftingCompletions.completed(guest, grid, message(UUID.randomUUID(), IRON, CraftingJobStatusPacket.Status.FINISHED));
        require(ranks(guest, grid).keySet().equals(Set.of(IRON)) && !ranked.containsKey(IRON), "Players shared completion order");
        try {
            var prune = AeCraftingCompletions.class.getDeclaredMethod("prune", LinkedHashMap.class, long.class); prune.setAccessible(true);
            for (var player : List.of(owner, guest)) prune.invoke(null, histories().get(server).get(player.getUUID()), server.overworld().getGameTime() + 12_000);
        } catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
        require(ranks(owner, grid).isEmpty() && ranks(guest, grid).isEmpty(), "Completed pins did not expire");
    }
    private static CraftingJobStatusPacket message(UUID id, AEKey key, CraftingJobStatusPacket.Status status) { return new CraftingJobStatusPacket(id, key, 1, 0, status); }
    @SuppressWarnings("unchecked") private static Map<AEKey, Integer> ranks(ServerPlayer player, IGrid grid) {
        try { var method = AeCraftingCompletions.class.getDeclaredMethod("ranks", ServerPlayer.class, IGrid.class); method.setAccessible(true); return (Map<AEKey, Integer>) method.invoke(null, player, grid); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    @SuppressWarnings("unchecked") private static Map<MinecraftServer, Map<UUID, LinkedHashMap<UUID, ?>>> histories() {
        try { var field = AeCraftingCompletions.class.getDeclaredField("SERVERS"); field.setAccessible(true); return (Map<MinecraftServer, Map<UUID, LinkedHashMap<UUID, ?>>>) field.get(null); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
    static void report(JsonObject report) {
        require(stages == 13 && !histories().containsKey(server), "Pin stages incomplete or logout retained history");
        report.addProperty("meCompletionPinsVerified", true); report.addProperty("meCompletionPinStages", stages); report.addProperty("completionHistoryCleared", true);
    }
    private MeCompletionPinsProbe() { }
}
