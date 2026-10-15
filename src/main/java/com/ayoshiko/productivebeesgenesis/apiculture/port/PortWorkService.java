package com.ayoshiko.productivebeesgenesis.apiculture.port;

import com.ayoshiko.productivebeesgenesis.apiculture.compat.VerifiedCageProjection;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.*;
import com.ayoshiko.productivebeesgenesis.apiculture.persistence.*;
import com.ayoshiko.productivebeesgenesis.apiculture.runtime.*;
import com.ayoshiko.productivebeesgenesis.apiary.*;
import java.util.*;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;

/** 全服预算中的有限供给：一轮只提交一次装蜂或补料，拒收保留原槽。 */
@EventBusSubscriber(modid = "productivebeesgenesis")
public final class PortWorkService {
    private static final Map<MinecraftServer, FairDueQueue<GlobalPos>> QUEUES = new HashMap<>();
    public static void watch(NetworkPortBlockEntity port) {
        if (port.failed() || port.kind() == PortState.Kind.OUTPUT || !NetworkPortBlockEntity.live(port)) return;
        var level = (ServerLevel) port.getLevel();
        QUEUES.computeIfAbsent(level.getServer(), ignored -> new FairDueQueue<>())
                .offer(GlobalPos.of(level.dimension(), port.getBlockPos()), level.getServer().overworld().getGameTime() + 1);
    }
    static void remove(NetworkPortBlockEntity port) {
        if (port.getLevel() instanceof ServerLevel level) {
            var queue = QUEUES.get(level.getServer()); if (queue != null) queue.remove(GlobalPos.of(level.dimension(), port.getBlockPos()));
        }
    }
    public static boolean step(MinecraftServer server) {
        var queue = QUEUES.get(server); if (queue == null) return false;
        long now = server.overworld().getGameTime(); var pos = queue.poll(now); if (pos == null) return false;
        var level = server.getLevel(pos.dimension());
        if (level == null) return true;
        var chunk = level.getChunkSource().getChunkNow(pos.pos().getX() >> 4, pos.pos().getZ() >> 4);
        if (chunk == null || !(chunk.getBlockEntity(pos.pos()) instanceof NetworkPortBlockEntity port) || !NetworkPortBlockEntity.live(port) || port.failed()) return true;
        try {
            var access = new PortAccess(port); var view = access.get(false);
            if (view == null) { queue.offer(pos, now + 20); return true; }
            if (!view.state().enabled()) return true;
            if (view.state().kind() == PortState.Kind.INPUT) {
                if (RuntimeProductPolicies.peek(level) == null) {
                    port.result(10);
                    // 与 ME 桥共用编译器和全服预算；单端口也按固定小批推进首次资格索引。
                    for (int work = 0; work < 8 && RuntimeProductPolicies.peek(level) == null; work++)
                        RuntimeProductPolicies.get(level, view.root().policyRevision());
                    queue.offer(pos, now + 1);
                } else port.result(0);
                return true;
            }
            if (view.state().supplies().stream().allMatch(PortState.Stack::empty)) return true;
            boolean moved = supply(access, view, level); port.result(moved ? 5 : view.state().target() == null ? 3 : 4);
            queue.offer(pos, now + (moved ? 1 : 20));
        } catch (RuntimeException failure) { port.isolate(failure); }
        return true;
    }
    private static boolean supply(PortAccess access, PortAccess.Snapshot view, ServerLevel level) {
        var port = view.state(); if (port.target() == null) return false;
        var record = view.root().ownedMachines().get(port.target());
        if (record == null) return false;
        // 暂停生产时也能安装蜜蜂；激活只由明确供给动作触发。
        if (!record.beeReady()) return ManagedBeeAccess.activate(level, view.authority(), record);
        var targetAccess = ManagedBeeAccess.capture(level, view.authority(), record);
        if (targetAccess == null || record.feedingSlots().isEmpty()) return false;
        for (int input = 0; input < PortState.SLOTS; input++) {
            var held = port.supplies().get(input); if (held.empty()) continue;
            var offered = PortCapabilities.item(held, level); var unit = offered.copyWithCount(1);
            var egg = BeeSpawnEggHelper.resolve(unit);
            if (egg != null || VerifiedCageProjection.supported(unit)) {
                for (int target = 0; target < record.workLayout().beeSlots(); target++) {
                    int slot = target; if (record.beeRecords().stream().anyMatch(bee -> bee.slot() == slot)) continue;
                    try {
                        var contents = egg == null ? VerifiedCageProjection.contents(unit) : BeeSpawnEggHelper.contents(level, egg);
                        var returned = egg == null ? VerifiedCageProjection.afterRelease(unit) : ItemStack.EMPTY;
                        var remaining = afterCage(port, input, returned, level); if (remaining == null) break;
                        var next = targetAccess.insert(target, contents).withPort(port, port.supplies(remaining));
                        if (!targetAccess.current() || !access.commit(view, next, false)) return false;
                        try { view.core().runtime().beeChanged(port.target(), target, true, level.getServer().overworld().getGameTime()); NetworkRuntimeService.watch(view.core(), true); }
                        catch (RuntimeException failure) { com.mojang.logging.LogUtils.getLogger().warn("Port bee is committed; runtime will rediscover it", failure); }
                        return true;
                    } catch (IllegalArgumentException unsupported) { break; }
                }
                continue;
            }
            for (var bee : record.beeRecords()) {
                if (!bee.enabled()) continue;
                try {
                    var feeding = record.feedingSlots();
                    if (StaticFeedingAdapter.flower(feeding, bee.slot(), ResourceLocation.parse(bee.plan().beeType()), level.registryAccess())) continue;
                    var plan = targetAccess.feeding(bee.slot(), StaticFeedingAdapter.fromStack(offered, level.registryAccess()), 1, true);
                    if (plan.moved() == 0) continue;
                    var fed = plan.next().ownedMachines().get(port.target()).feedingSlots();
                    if (!StaticFeedingAdapter.flower(fed, bee.slot(), ResourceLocation.parse(bee.plan().beeType()), level.registryAccess())) continue;
                    var rest = plan.moved() == held.count() ? PortState.Stack.EMPTY : new PortState.Stack(held.key(), held.count() - plan.moved());
                    var next = plan.next().withPort(port, port.stack(input, rest));
                    return targetAccess.current() && access.commit(view, next, false);
                } catch (IllegalArgumentException unsupported) { /* 无匹配花源或不接受的物品保留在供给槽。 */ }
            }
        }
        return false;
    }
    /** 普通笼消耗，加固笼留空壳；没有空壳位置就不装蜂。 */
    static List<PortState.Stack> afterCage(PortState port, int slot, ItemStack returned, ServerLevel level) {
        var result = new ArrayList<>(port.supplies()); var input = result.get(slot);
        result.set(slot, input.count() == 1 ? PortState.Stack.EMPTY : new PortState.Stack(input.key(), input.count() - 1));
        if (returned.isEmpty()) return result;
        var output = PortCapabilities.stack(returned, level); int limit = Math.min(64, returned.getMaxStackSize());
        for (int offset = 0; offset < result.size(); offset++) {
            int target = (slot + offset) % result.size(); var cell = result.get(target);
            if ((cell.empty() || cell.key().equals(output.key())) && cell.count() + output.count() <= limit) {
                result.set(target, new PortState.Stack(output.key(), cell.count() + output.count())); return result;
            }
        }
        return null;
    }
    @SubscribeEvent public static void stopped(ServerStoppedEvent event) { QUEUES.remove(event.getServer()); }
    private PortWorkService() { }
}
