package com.ayoshiko.productivebeesgenesis.domainprobe;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.ProductKeyCodec;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import com.ayoshiko.productivebeesgenesis.apiculture.terminal.*;
import com.google.gson.JsonObject;
import java.util.*;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import static com.ayoshiko.productivebeesgenesis.domainprobe.DomainProbeServer.require;

/** 复用两名 TCP 玩家和正式菜单；先冻结各自选择，再竞争同一权威资产。 */
final class TerminalPermissionProbe {
    static boolean enabled() { return Boolean.getBoolean("pbg.concurrent.terminals"); }
    private static CompetitionAssets.Total total;
    private static UUID member;
    private static List<NetworkCoreMenu> closedMenus;
    private static boolean completed;
    private static final com.google.gson.JsonArray stages = new com.google.gson.JsonArray();

    static void place(NetworkCoreBlockEntity core) {
        var level = core.getLevel(); var pos = core.getBlockPos();
        level.setBlockAndUpdate(pos.north(), NetworkContent.BEE_TERMINAL.get().defaultBlockState());
        level.setBlockAndUpdate(pos.south(), NetworkContent.CENTRIFUGE_TERMINAL.get().defaultBlockState());
        level.setBlockAndUpdate(pos.west(), NetworkContent.COMBINED_TERMINAL.get().defaultBlockState());
    }
    static void seed(NetworkCoreBlockEntity core, List<ServerPlayer> players) {
        total = CompetitionAssets.capture(core, players);
        var authority = core.ownership().readyAuthority();
        var record = authority.checkpoint().ownedMachines().activeValues(TerminalScope.APIARY.machine()).iterator().next();
        member = record.claim().member();
        var holder = players.stream().filter(p -> p.getInventory().getItem(1).has(net.minecraft.core.component.DataComponents.CUSTOM_DATA)).findFirst().orElseThrow();
        core.openTerminal(holder);
        require(((NetworkCoreMenu) holder.containerMenu).exchangeBee(holder, member, 0, record.bees().revision(), null, 1,
                CoreBeeCageExchange.Action.INSERT, false).moved() == 1, "Permission fixture could not return held bee");
        var centrifuge = authority.checkpoint().ownedMachines().activeValues(TerminalScope.CENTRIFUGE.machine()).iterator().next();
        if (centrifuge.centrifuge() == null) {
            var comb = new ItemStack(cy.jdkdigital.productivebees.init.ModItems.CONFIGURABLE_HONEYCOMB.get());
            comb.set(cy.jdkdigital.productivebees.init.ModDataComponents.BEE_TYPE.get(), net.minecraft.resources.ResourceLocation.parse("productivebees:iron"));
            var policy = new ProductPolicyRegistry(com.ayoshiko.productivebeesgenesis.apiculture.compat.PbProductPolicyCompiler.compile(holder.serverLevel(), authority.checkpoint().policyRevision()).snapshot());
            require(new com.ayoshiko.productivebeesgenesis.apiculture.centrifuge.NetworkCentrifugeService(authority,
                    NetworkPersistence.directory(holder.server), policy).activate(holder.serverLevel(), centrifuge.claim().member(),
                    authority.checkpoint().revision(), ProductKeyCodec.item(comb, holder.registryAccess())), "Permission centrifuge activation failed");
        }
        open(core, players, false);
        require(total.equals(CompetitionAssets.capture(core, players)), "Permission fixture changed assets");
    }
    private static void open(NetworkCoreBlockEntity core, List<ServerPlayer> players, boolean centrifuge) {
        var level = players.getFirst().serverLevel(); var pos = core.getBlockPos();
        require(NetworkTerminalAccess.open(players.getFirst(), (NetworkTerminalBlockEntity) level.getBlockEntity(centrifuge ? pos.south() : pos.north())), "Owner terminal failed");
        require(NetworkTerminalAccess.open(players.get(1), (NetworkTerminalBlockEntity) level.getBlockEntity(pos.west())), "Guest combined terminal failed");
        if (centrifuge) require(((NetworkCoreMenu) players.get(1).containerMenu).clickMenuButton(players.get(1), 11), "Combined switch failed");
        for (var player : players) {
            var menu = (NetworkCoreMenu) player.containerMenu;
            require(menu.scope() == (centrifuge ? TerminalScope.CENTRIFUGE : TerminalScope.APIARY)
                    && !menu.canManage(), "Wrong terminal scope or owner controls");
            for (int button = 0; button < 4; button++) require(!menu.clickMenuButton(player, button), "Terminal exposed core controls");
        }
        require(!((NetworkCoreMenu) players.get(1).containerMenu).canUpgrade(), "Ordinary guest gained upgrades");
    }
    static boolean ready(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage) {
        return stage != 213 && stage != 215 || core.allowed(players.get(1)) == (stage == 215);
    }
    static int advance(NetworkCoreBlockEntity core, List<ServerPlayer> players, int stage,
            Map<UUID, CompetitionSignal> replies, NetworkCheckpoint before, Map<UUID, ListTag> inventories) throws Exception {
        var current = core.ownership().readyAuthority().checkpoint();
        require(total.equals(CompetitionAssets.capture(core, players)), "Terminal competition changed asset total at " + stage);
        require(current.energy().equals(before.energy()) && current.scheduler().equals(before.scheduler())
                && current.transfers().equals(before.transfers()), "Terminal command changed FE or ownership");
        if (stage == 201 || stage == 204) {
            require(count(replies, TerminalReply.Status.OK) == 1 && count(replies, TerminalReply.Status.STALE) == 1, "Old bee selection survived competing control: " + replies);
            var old = before.ownedMachines().get(member).bees().bee(0); var bee = current.ownedMachines().get(member).bees().bee(0);
            require(bee.enabled() == (stage == 204) && old.id().equals(bee.id()) && old.progress() == bee.progress()
                    && old.random().equals(bee.random()) && old.frozen().equals(bee.frozen()), "Control changed paid bee work");
        } else if (stage == 208) {
            require(replies.values().stream().mapToInt(CompetitionSignal::moved).sum() == 3 && count(replies, TerminalReply.Status.MOVED) == 2, "Cross-terminal partial transfer incorrect");
            var gold = current.ledger().balances().entrySet().stream().filter(e -> e.getKey().id().toString().equals("minecraft:gold_ingot")).findFirst().orElseThrow();
            require(gold.getValue().equals(ProductAmount.of(1)), "Cross-terminal gold remainder incorrect");
        } else if (stage == 220) {
            require(count(replies, TerminalReply.Status.MOVED) == 1 && count(replies, TerminalReply.Status.STALE) == 1
                    && current.ownedMachines().get(member).bees().bees().isEmpty(), "Cross-terminal cage did not have one winner");
        } else {
            require(current == before && inventories.equals(CompetitionAssets.inventories(players)), "Rejected/query request changed assets at " + stage);
            if (stage == 206 || stage == 211) require(replies.get(players.get(1).getUUID()).status() == TerminalReply.Status.UNAVAILABLE.ordinal(), "Guest upgrade was not denied");
            if (stage == 218) require(count(replies, TerminalReply.Status.INVALID) == 2, "Malformed bee control accepted");
        }
        if (stage == 206) open(core, players, true);
        if (stage == 211 || stage == 216) open(core, players, false);
        if (stage == 221) {
            require(index(core).order() != null, "Quantity subscribers did not retain shared index");
            closedMenus = players.stream().map(p -> (NetworkCoreMenu) p.containerMenu).toList();
        }
        if (stage == 222) {
            require(count(replies, TerminalReply.Status.OK) == 2 && index(core).order() == null
                    && closedMenus.stream().allMatch(m -> m.terminalSelectionPage() == null), "Cancel retained query roots");
        }
        if (stage == 223) {
            for (int i = 0; i < players.size(); i++) require(players.get(i).containerMenu == players.get(i).inventoryMenu
                    && !closedMenus.get(i).stillValid(players.get(i)), "Closed terminal retained access");
            require(index(core).order() == null, "Close retained quantity index");
            open(core, players, false); closedMenus = null;
        }
        var evidence = new JsonObject(); evidence.addProperty("stage", stage);
        for (var player : players) {
            var reply = replies.get(player.getUUID()); var result = new JsonObject();
            result.addProperty("moved", reply.moved()); result.addProperty("status", reply.status());
            evidence.add(player == players.getFirst() ? "owner" : "guest", result);
        }
        stages.add(evidence);
        if (stage == 224) { completed = true; return -1; }
        return stage + 1;
    }
    private static long count(Map<UUID, CompetitionSignal> replies, TerminalReply.Status status) {
        return replies.values().stream().filter(r -> r.status() == status.ordinal()).count();
    }
    private static ProductQuantityIndex index(NetworkCoreBlockEntity core) throws Exception {
        var method = NetworkCoreBlockEntity.class.getDeclaredMethod("quantityIndex"); method.setAccessible(true);
        return (ProductQuantityIndex) method.invoke(core);
    }
    static void report(JsonObject report) {
        require(completed && stages.size() == 25, "Terminal permission stages incomplete");
        report.add("terminalPermissionStages", stages);
        report.addProperty("terminalPermissionsTwoTcpPlayers", true);
        report.addProperty("terminalSharedProductsBeeControlsAndCages", true);
        report.addProperty("terminalGuestUpgradeDeniedBothTypes", true);
        report.addProperty("terminalRevocationRegrantForgeryAndCleanup", true);
    }
    private TerminalPermissionProbe() { }
}
