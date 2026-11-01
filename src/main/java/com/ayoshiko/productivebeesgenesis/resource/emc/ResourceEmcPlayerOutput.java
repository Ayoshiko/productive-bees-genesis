package com.ayoshiko.productivebeesgenesis.resource.emc;

import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import com.ayoshiko.productivebeesgenesis.resource.ResourceHiveHost;
import com.ayoshiko.productivebeesgenesis.resource.ResourceMenuSync;
import com.ayoshiko.productivebeesgenesis.resource.ResourceOutputAccess;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.utils.PlayerHelper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

/** 在线玩家账户直接入账；资源交接与客户端同步分离，同步重试不能再次加钱。 */
public final class ResourceEmcPlayerOutput {
    private static final Map<MinecraftServer, Map<UUID, Long>> PENDING_SYNC = new IdentityHashMap<>();
    private ResourceEmcPlayerOutput() {}

    public enum State { READY, OFFLINE, NOT_IN_TEAM, UNAVAILABLE }
    private record Target(ResourceHiveHost host, UUID recipient, ServerPlayer player, IKnowledgeProvider knowledge) {}

    public static State state(BlockEntity host) {
        var source = ResourceHiveHost.existing(host);
        if (source == null || source.reference() == null || !(host.getLevel() instanceof ServerLevel level)) return State.UNAVAILABLE;
        UUID recipient = source.emcRecipient(host);
        if (!FtbTeamEmcRecipients.sameTeam(level.getServer(), source.account(host).owner(), recipient)) return State.NOT_IN_TEAM;
        var player = level.getServer().getPlayerList().getPlayer(recipient);
        if (player == null || player.isRemoved()) return State.OFFLINE;
        return player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null) == null ? State.UNAVAILABLE : State.READY;
    }

    public static boolean canReceive(BlockEntity host) {
        if (ResourceHiveHost.existing(host) == null || ResourceHiveHost.existing(host).reference() == null) {
            if (!(host.getLevel() instanceof ServerLevel level)
                    || !(host instanceof mekanism.common.lib.security.ISecurityTile security)
                    || security.getOwnerUUID() == null) return false;
            var player = level.getServer().getPlayerList().getPlayer(security.getOwnerUUID());
            return player != null && !player.isRemoved() && player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null) != null;
        }
        return state(host) == State.READY;
    }

    public static boolean hasRecipient(BlockEntity host) {
        var source = ResourceHiveHost.existing(host);
        return source != null && source.reference() != null && host.getLevel() instanceof ServerLevel;
    }

    public static ResourceMenuSync.EmcRecipient snapshot(BlockEntity host, ServerPlayer viewer) {
        var source = ResourceHiveHost.existing(host);
        UUID recipient = source.emcRecipient(host);
        var server = ((ServerLevel) host.getLevel()).getServer();
        var player = server.getPlayerList().getPlayer(recipient);
        var profile = server.getProfileCache() == null ? null : server.getProfileCache().get(recipient).orElse(null);
        String name = recipient.toString();
        if (player != null) name = player.getGameProfile().getName();
        else if (profile != null) name = profile.getName();
        UUID owner = source.account(host).owner();
        boolean selectable = (owner.equals(viewer.getUUID()) || viewer.hasPermissions(2))
                && source.account(host).canExtract(source.reference(), ResourceTypes.EMC)
                && (!owner.equals(recipient) || server.getPlayerList().getPlayers().stream()
                        .anyMatch(other -> !owner.equals(other.getUUID()) && FtbTeamEmcRecipients.sameTeam(server, owner, other.getUUID())));
        return new ResourceMenuSync.EmcRecipient(recipient, name, state(host).name().toLowerCase(java.util.Locale.ROOT), selectable);
    }

    private static Target target(BlockEntity host) {
        if (state(host) != State.READY) return null;
        var source = ResourceHiveHost.existing(host);
        UUID recipient = source.emcRecipient(host);
        var player = ((ServerLevel) host.getLevel()).getServer().getPlayerList().getPlayer(recipient);
        var knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null);
        return knowledge == null ? null : new Target(source, recipient, player, knowledge);
    }

    public static BigInteger transfer(ResourceOutputAccess port, BigInteger maximum) {
        var host = port.sourceHost();
        if (host == null) return BigInteger.ZERO;
        var target = target(host);
        if (target == null) return BigInteger.ZERO;
        var server = ((ServerLevel) host.getLevel()).getServer();
        BigInteger accepted = ResourceHostApi.transferExact(host, ResourceTypes.EMC, maximum, offer -> {
            validate(port, host, target);
            BigInteger before = target.knowledge.getEmc();
            if (before == null || before.signum() < 0) throw new IllegalStateException("Invalid ProjectE player balance");
            BigInteger after = before.add(offer.amount());
            // 即使后续校验失败，也需要同步已经可能发生的写入；未决资源请求保持隔离。
            PENDING_SYNC.computeIfAbsent(server, ignored -> new LinkedHashMap<>()).putIfAbsent(target.recipient, 0L);
            target.knowledge.setEmc(after);
            validate(port, host, target);
            if (!after.equals(target.player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null).getEmc())) {
                throw new IllegalStateException("ProjectE player credit was not exact");
            }
            return offer.amount();
        });
        return accepted;
    }

    private static void validate(ResourceOutputAccess port, BlockEntity host, Target target) {
        var server = ((ServerLevel) host.getLevel()).getServer();
        if (port.sourceHost() != host || ResourceHiveHost.existing(host) != target.host
                || !target.recipient.equals(target.host.emcRecipient(host))
                || server.getPlayerList().getPlayer(target.recipient) != target.player || target.player.isRemoved()
                // ProjectE 每次能力查询返回新包装，权威身份是玩家及其持久附件。
                || target.player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null) == null
                || !FtbTeamEmcRecipients.sameTeam(server, target.host.account(host).owner(), target.recipient)) {
            throw new IllegalStateException("EMC recipient changed during credit");
        }
    }

    public static boolean cycle(BlockEntity host, UUID expected) {
        var source = ResourceHiveHost.existing(host);
        if (source == null || !source.emcRecipient(host).equals(expected)) return false;
        var server = ((ServerLevel) host.getLevel()).getServer();
        UUID owner = source.account(host).owner();
        var recipients = new ArrayList<UUID>();
        recipients.add(owner);
        server.getPlayerList().getPlayers().stream()
                .filter(player -> !owner.equals(player.getUUID()) && !player.isRemoved()
                        && FtbTeamEmcRecipients.sameTeam(server, owner, player.getUUID()))
                .sorted(Comparator.comparing(player -> player.getGameProfile().getName()))
                .forEach(player -> recipients.add(player.getUUID()));
        if (recipients.size() == 1 && owner.equals(expected)) return false;
        int index = recipients.indexOf(expected);
        source.setEmcRecipient(host, recipients.get((index + 1) % recipients.size()));
        return true;
    }

    public static void sync(MinecraftServer server) {
        if (!server.isSameThread()) throw new IllegalStateException("EMC sync requires the server thread");
        var pending = PENDING_SYNC.get(server);
        if (pending == null) return;
        long tick = server.getTickCount();
        if (tick % 10 != 0) return;
        for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
            var entry = iterator.next();
            var player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                iterator.remove();
                continue;
            }
            if (tick < entry.getValue()) continue;
            try {
                var knowledge = player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY).orElse(null);
                if (knowledge == null) {
                    iterator.remove();
                    continue;
                }
                knowledge.syncEmc(player);
                PlayerHelper.updateScore(player, PlayerHelper.SCOREBOARD_EMC, knowledge.getEmc());
                iterator.remove();
            } catch (RuntimeException | LinkageError failure) {
                entry.setValue(tick + 100);
                LogThrottle.warnWithCooldown("emc_player_sync", 10000, "ProjectE EMC display sync failed; credited amount will not be replayed", failure);
            }
        }
        if (pending.isEmpty()) PENDING_SYNC.remove(server);
    }

    public static void clear(MinecraftServer server) { PENDING_SYNC.remove(server); }
}
