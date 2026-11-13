package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.logistics.OutputCustody;
import com.ayoshiko.productivebeesgenesis.mek.DeferredItemOutput;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 同刻普通工厂输出的派生视图；排队只保留原所有者，执行前逐机登记可保存的贡献。 */
final class Ae2DeferredItemOutputs {
    static final int MAX_OWNERS = 4096;
    static final int MAX_CONTRIBUTIONS = 65536;
    private static final Map<MinecraftServer, Pass> SERVERS = new IdentityHashMap<>();

    private Ae2DeferredItemOutputs() {}

    static boolean enqueue(DeferredItemOutput output) {
        BlockEntity owner = output == null ? null : output.owner();
        if (owner == null || !(owner.getLevel() instanceof ServerLevel level)) return false;
        MinecraftServer server = level.getServer();
        if (!server.isSameThread() || !(owner instanceof IAe2OutputHostBase host)
                || !output.available(output.epoch())) return false;
        Ae2OutputStateHolder holder = host.productivebeesgenesis$getAe2StateHolder();
        if (holder == null) return false;
        long revision = holder.getOutputRevision();
        Pass pass = SERVERS.computeIfAbsent(server, ignored -> new Pass());
        if (pass.flushing) return false;
        long tick = server.getTickCount();
        if (pass.tick != tick) {
            cancelTickets(pass);
            pass.tick = tick;
            pass.cancelled = false;
        }
        int groups = output.groupCount();
        if (groups <= 0 || groups > MAX_CONTRIBUTIONS || pass.cancelled) return false;
        Ticket previous = pass.owners.get(output);
        if (previous != null && previous.epoch == output.epoch()) {
            if (previous.holder == holder && previous.revision == revision) return true;
            cancelTicket(previous, "changed output");
            return false;
        }
        if (pass.tickets.size() >= MAX_OWNERS || groups > MAX_CONTRIBUTIONS - pass.groups) return false;
        Ticket ticket = new Ticket(output, output.epoch(), groups, holder, revision);
        pass.tickets.add(ticket);
        pass.owners.put(output, ticket);
        pass.groups += groups;
        return true;
    }

    static void flush(MinecraftServer server) {
        Pass pass = SERVERS.get(server);
        if (pass == null || pass.flushing) return;
        if (!server.isSameThread()) throw new IllegalStateException("Deferred outputs require the server thread");
        if (pass.tick != server.getTickCount() || pass.cancelled) {
            cancel(server);
            return;
        }
        pass.flushing = true;
        List<Endpoint> claimed = new ArrayList<>(pass.tickets.size());
        Map<NetworkKey, List<Contribution>> keys = new LinkedHashMap<>();
        try {
            for (Ticket ticket : pass.tickets) {
                try {
                    Endpoint endpoint = resolve(server, ticket);
                    if (endpoint == null || !ticket.output.claim(ticket.epoch)) {
                        cancelTicket(ticket, "unavailable");
                        continue;
                    }
                    claimed.add(endpoint);
                    for (int group = 0; group < ticket.output.groupCount(); group++) {
                        long amount = ticket.output.amount(group);
                        ItemStack template = ticket.output.template(group);
                        AEItemKey key = AEItemKey.of(template);
                        if (amount <= 0 || key == null) continue;
                        NetworkKey identity = new NetworkKey(endpoint.storage, endpoint.source, key);
                        keys.computeIfAbsent(identity, ignored -> new ArrayList<>())
                                .add(new Contribution(endpoint, group, template, amount));
                    }
                } catch (RuntimeException | LinkageError failure) {
                    cancelTicket(ticket, "prepare failure");
                    log(ticket, "prepare", failure);
                }
            }
            pass.keyRotations.keySet().retainAll(keys.keySet());
            for (var entry : keys.entrySet()) {
                if (pass.cancelled) break;
                try {
                    List<Contribution> contributions = entry.getValue();
                    int rotation = Math.floorMod(pass.keyRotations.getOrDefault(entry.getKey(), 0), contributions.size());
                    while (rotation > 0 && rotation < contributions.size()
                            && contributions.get(rotation - 1).endpoint == contributions.get(rotation).endpoint) {
                        rotation++;
                    }
                    if (rotation == contributions.size()) rotation = 0;
                    int next = rotation + 1;
                    while (next < contributions.size()
                            && contributions.get(next).endpoint == contributions.get(rotation).endpoint) next++;
                    pass.keyRotations.put(entry.getKey(), next % contributions.size());
                    Collections.rotate(contributions, -rotation);
                    flushKey(entry.getKey(), entry.getValue());
                } catch (RuntimeException | LinkageError failure) {
                    for (Contribution contribution : entry.getValue()) {
                        cancelTicket(contribution.endpoint.ticket, "key failure");
                    }
                    log(entry.getValue().getFirst().endpoint.ticket, "key", failure);
                }
            }
        } finally {
            for (Endpoint endpoint : claimed) {
                try {
                    endpoint.ticket.output.finish(endpoint.ticket.epoch);
                } catch (RuntimeException | LinkageError failure) {
                    cancelTicket(endpoint.ticket, "finish failure");
                    log(endpoint.ticket, "finish", failure);
                }
            }
            pass.flushing = false;
            pass.tickets.clear();
            pass.owners.clear();
            pass.groups = 0;
            keys.clear();
            claimed.clear();
            if (pass.cancelled) SERVERS.remove(server, pass);
        }
    }

    private static Endpoint resolve(MinecraftServer server, Ticket ticket) {
        DeferredItemOutput output = ticket.output;
        BlockEntity owner = output.owner();
        if (!output.available(ticket.epoch) || owner == null || owner.isRemoved()
                || !(owner.getLevel() instanceof ServerLevel level) || level.getServer() != server
                || !level.hasChunkAt(owner.getBlockPos()) || level.getBlockEntity(owner.getBlockPos()) != owner
                || !(owner instanceof IAe2OutputHostBase host)) return null;
        if (host.productivebeesgenesis$getAe2StateHolder() != ticket.holder
                || ticket.holder.getOutputRevision() != ticket.revision) return null;
        OutputCustody custody = OutputCustody.of(owner);
        if (custody.blocked()) return null;
        Ae2DirectItemPushSession session = Ae2OutputPusher.prepareDirectItemPush(host);
        if (session == null || !output.available(ticket.epoch)
                || ticket.holder.getOutputRevision() != ticket.revision) return null;
        return new Endpoint(ticket, host, level, custody, session, session.batchTarget(),
                session.batchSource(), session.batchTick());
    }

    private static void flushKey(NetworkKey key, List<Contribution> contributions) {
        Part part = new Part(key);
        for (Contribution contribution : contributions) {
            long remaining = contribution.amount;
            while (remaining > 0) {
                long piece = Math.min(remaining, Long.MAX_VALUE - part.amount);
                part.add(contribution, piece);
                remaining -= piece;
                if (part.amount == Long.MAX_VALUE) {
                    if (!execute(part)) return;
                    part = new Part(key);
                }
            }
        }
        if (part.amount > 0) execute(part);
    }

    /** 返回是否全接收；同键第一次部分或零接收后不再重复请求后续溢出组。 */
    private static boolean execute(Part part) {
        Part candidate = part;
        int attempts = part.participants.size() + 1;
        while (attempts-- > 0 && candidate.amount > 0) {
            Execution result = executePrepared(candidate);
            if (result != Execution.RETRY_PREPARATION) return result == Execution.COMPLETE;
            Part remaining = new Part(part.key);
            for (PartSource source : candidate.sources) {
                if (eligible(source.contribution.endpoint, part.key.key)) {
                    remaining.add(source.contribution, source.amount);
                }
            }
            // 每次准备重组必须剔除失效来源；未发起外部调用，健康来源可继续。
            if (remaining.participants.size() >= candidate.participants.size()) return true;
            candidate = remaining;
        }
        return true;
    }

    private static boolean eligible(Endpoint endpoint, AEItemKey key) {
        try {
            if (endpoint.current() && !endpoint.custody.blocked()) {
                return endpoint.session.canReserve(key);
            }
        } catch (RuntimeException | LinkageError failure) {
            log(endpoint.ticket, "eligibility", failure);
        }
        cancelTicket(endpoint.ticket, "ineligible");
        return false;
    }

    private static Execution executePrepared(Part part) {
        for (Participant participant : part.participants) {
            if (!eligible(participant.endpoint, part.key.key)) return Execution.RETRY_PREPARATION;
        }
        Endpoint leader = part.sources.getFirst().contribution.endpoint;
        // 会话在整轮开始时已准备；前一键的真实调用可能已经耗尽网络预算。
        if (!leader.ticket.holder.tryAcquireNetworkWork(leader.storage, leader.tick)) return Execution.STOP_KEY;
        UUID request = UUID.randomUUID();
        boolean externalStarted = false;
        try {
            for (Participant participant : part.participants) {
                OutputCustody custody = participant.endpoint.custody;
                try {
                    participant.prepared = custody.beginSharedItem(request, part.sources.getFirst().contribution.template,
                            participant.amount, "ae2-shared-item");
                } catch (RuntimeException | LinkageError failure) {
                    participant.prepared = request.equals(custody.sequence());
                    cancelTicket(participant.endpoint.ticket, "prepare notification");
                    log(participant.endpoint.ticket, "prepare notification", failure);
                    return Execution.RETRY_PREPARATION;
                }
                if (!participant.prepared) {
                    cancelTicket(participant.endpoint.ticket, "prepare refused");
                    return Execution.RETRY_PREPARATION;
                }
            }
            // 保存、升级或断网可发生在登记回调中；尚未发起外部调用时原资产仍可安全保留。
            for (Participant participant : part.participants) {
                if (!participant.endpoint.current()) {
                    cancelTicket(participant.endpoint.ticket, "changed during preparation");
                    return Execution.RETRY_PREPARATION;
                }
            }
            long accepted;
            Throwable unknown = null;
            try {
                externalStarted = true;
                accepted = leader.session.insertBatch(part.key.key, part.amount);
                if (accepted < 0 || accepted > part.amount) throw new IllegalStateException("Invalid shared AE item receipt");
            } catch (RuntimeException | LinkageError failure) {
                accepted = part.amount;
                unknown = failure;
            }
            long cost = leader.session.batchCostNanos();
            long remaining = accepted;
            for (PartSource source : part.sources) {
                source.accepted = unknown == null ? Math.min(remaining, source.amount) : source.amount;
                source.participant.accepted += source.accepted;
                remaining -= source.accepted;
            }
            for (Participant participant : part.participants) {
                try {
                    if (unknown == null) participant.endpoint.custody.sharedItemReceipt(participant.accepted);
                    else participant.endpoint.custody.sharedItemUncertain(unknown);
                } catch (RuntimeException | LinkageError failure) {
                    log(participant.endpoint.ticket, "receipt", failure);
                    if (participant.accepted > 0
                            && participant.endpoint.custody.sourceDebitPending() != participant.accepted) {
                        participant.failed = true;
                        isolateSourceDebit(participant.endpoint, failure);
                    }
                }
            }
            for (PartSource source : part.sources) {
                if (source.accepted == 0 || source.participant.failed) continue;
                Endpoint endpoint = source.contribution.endpoint;
                try {
                    endpoint.ticket.output.debit(source.contribution.group, source.accepted);
                    endpoint.custody.confirmSourceDebit(source.accepted);
                } catch (RuntimeException | LinkageError failure) {
                    source.participant.failed = true;
                    if (endpoint.custody.sourceDebitPending() > 0) isolateSourceDebit(endpoint, failure);
                    log(endpoint.ticket, "source debit", failure);
                }
            }
            long sharedCost = cost / part.participants.size();
            long costRemainder = cost % part.participants.size();
            for (Participant participant : part.participants) {
                Endpoint endpoint = participant.endpoint;
                long participantCost = sharedCost;
                if (costRemainder > 0) {
                    participantCost++;
                    costRemainder--;
                }
                endpoint.session.recordBatch(endpoint.storage, endpoint.tick, part.key.key,
                        unknown == null ? participant.accepted : 0,
                        unknown == null && accepted > 0, participantCost);
            }
            return unknown == null && accepted == part.amount ? Execution.COMPLETE : Execution.STOP_KEY;
        } catch (RuntimeException | LinkageError failure) {
            log(part.sources.getFirst().contribution.endpoint.ticket, "shared preparation", failure);
            return Execution.STOP_KEY;
        } finally {
            // 没有执行外部调用时撤销已登记的贡献；任何来源都不扣量。
            if (!externalStarted) {
                for (Participant participant : part.participants) {
                    if (!participant.prepared) continue;
                    try {
                        participant.endpoint.custody.sharedItemReceipt(0);
                    } catch (RuntimeException | LinkageError failure) {
                        log(participant.endpoint.ticket, "cancel preparation", failure);
                    }
                }
            }
        }
    }

    private enum Execution { COMPLETE, RETRY_PREPARATION, STOP_KEY }

    static void cancel(MinecraftServer server) {
        Pass pass = SERVERS.get(server);
        if (pass == null) return;
        pass.cancelled = true;
        for (Ticket ticket : pass.tickets) {
            cancelTicket(ticket, "cancel");
        }
        if (!pass.flushing) {
            cancelTickets(pass);
            SERVERS.remove(server);
        }
    }

    static void clearAll() {
        for (MinecraftServer server : List.copyOf(SERVERS.keySet())) cancel(server);
    }

    private static void cancelTickets(Pass pass) {
        for (Ticket ticket : pass.tickets) {
            cancelTicket(ticket, "retire");
        }
        pass.tickets.clear();
        pass.owners.clear();
        pass.groups = 0;
    }

    private static void cancelTicket(Ticket ticket, String phase) {
        try {
            ticket.output.cancel(ticket.epoch);
        } catch (RuntimeException | LinkageError failure) {
            log(ticket, phase, failure);
        }
    }

    private static void isolateSourceDebit(Endpoint endpoint, Throwable failure) {
        try {
            endpoint.custody.markSourceDebitUnknown(failure);
        } catch (RuntimeException | LinkageError notificationFailure) {
            if (notificationFailure != failure) failure.addSuppressed(notificationFailure);
            log(endpoint.ticket, "source debit notification", failure);
        }
    }

    private static void log(Ticket ticket, String phase, Throwable failure) {
        BlockEntity owner = ticket.output.owner();
        ProductiveBeesGenesis.LOGGER.error("普通物品合并交付失败，phase={} pos={} epoch={}；原资产继续保管",
                phase, owner == null ? null : owner.getBlockPos(), ticket.epoch, failure);
    }

    private static final class Pass {
        private final List<Ticket> tickets = new ArrayList<>();
        private final Map<DeferredItemOutput, Ticket> owners = new IdentityHashMap<>();
        /** 只保留本刻活跃网络键；部分接收时轮换首个贡献来源。 */
        private final Map<NetworkKey, Integer> keyRotations = new HashMap<>();
        private long tick = Long.MIN_VALUE;
        private int groups;
        private boolean flushing;
        private boolean cancelled;
    }

    private record Ticket(DeferredItemOutput output, long epoch, int groups,
            Ae2OutputStateHolder holder, long revision) {}

    private record NetworkKey(MEStorage storage, IActionSource source, AEItemKey key) {
        @Override
        public boolean equals(Object other) {
            return other instanceof NetworkKey identity && storage == identity.storage
                    && source == identity.source && key.equals(identity.key);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * System.identityHashCode(storage) + System.identityHashCode(source)) + key.hashCode();
        }
    }

    private record Endpoint(Ticket ticket, IAe2OutputHostBase host, ServerLevel level, OutputCustody custody,
            Ae2DirectItemPushSession session, MEStorage storage, IActionSource source, long tick) {
        private boolean current() {
            BlockEntity owner = ticket.output.owner();
            return ticket.output.available(ticket.epoch) && owner != null && !owner.isRemoved()
                    && owner.getLevel() == level && level.hasChunkAt(owner.getBlockPos())
                    && level.getBlockEntity(owner.getBlockPos()) == owner
                    && host.productivebeesgenesis$isDirectAeOutputEnabled()
                    && host.productivebeesgenesis$isOutputPushEnabled()
                    && host.productivebeesgenesis$isAeItemOutputEnabled()
                    && host.productivebeesgenesis$getAe2StateHolder() == ticket.holder
                    && ticket.holder.getOutputRevision() == ticket.revision
                    && ticket.holder.getPushState().getCachedNodeState(host) == Ae2GridNodeManager.STATE_ONLINE
                    && session.batchTarget() == storage && session.batchTick() == tick
                    && level.getGameTime() == tick
                    && Ae2GridNodeManager.getCachedMeStorage(host) == storage;
        }
    }

    private record Contribution(Endpoint endpoint, int group, ItemStack template, long amount) {}

    private static final class Part {
        private final NetworkKey key;
        private final List<PartSource> sources = new ArrayList<>();
        private final List<Participant> participants = new ArrayList<>();
        private long amount;

        private Part(NetworkKey key) {
            this.key = key;
        }

        private void add(Contribution contribution, long count) {
            // 来源按票据连续入组；分片和失败重组都保持顺序，只需合并尾部同一端点。
            Participant participant = participants.isEmpty() ? null : participants.getLast();
            if (participant == null || participant.endpoint != contribution.endpoint) {
                participant = new Participant(contribution.endpoint);
                participants.add(participant);
            }
            participant.amount = Math.addExact(participant.amount, count);
            sources.add(new PartSource(contribution, participant, count));
            amount = Math.addExact(amount, count);
        }
    }

    private static final class Participant {
        private final Endpoint endpoint;
        private long amount;
        private long accepted;
        private boolean prepared;
        private boolean failed;

        private Participant(Endpoint endpoint) {
            this.endpoint = endpoint;
        }
    }

    private static final class PartSource {
        private final Contribution contribution;
        private final Participant participant;
        private final long amount;
        private long accepted;

        private PartSource(Contribution contribution, Participant participant, long amount) {
            this.contribution = contribution;
            this.participant = participant;
            this.amount = amount;
        }
    }
}
