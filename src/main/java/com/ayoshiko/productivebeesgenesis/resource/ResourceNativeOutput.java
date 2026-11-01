package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.api.resource.ResourceHostApi;
import mekanism.api.Action;
import mekanism.api.MekanismAPI;
import mekanism.api.chemical.ChemicalStack;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** 配置、预检和手动／自动输出共用同一个接收边界；每次外部调用都在服务器线程。 */
public final class ResourceNativeOutput {
    public interface Receiver {
        long simulate(long maximum);
        long execute(long amount);
        void validate();
    }
    private static final Direction[] SIDES = Direction.values();
    private final ResourceOutputAccess access;
    private Map<ResourceKey, Long> observed;
    private List<ResourceKey> keys = List.of();
    private int cursor;
    private final Map<ResourceKey, Integer> faces = new java.util.HashMap<>();
    private long nextAttempt;
    private boolean reportedFailure;

    ResourceNativeOutput(ResourceOutputAccess access) { this.access = access; }
    void invalidate() {
        if (access.getLevel() instanceof ServerLevel level) ResourceRuntime.releaseNativeOutput(level.getServer(), this);
        observed = null;
        keys = List.of();
        faces.clear();
    }
    void changed() {
        nextAttempt = 0;
        observed = null;
    }
    void tick() {
        var host = access.sourceHost();
        if (host == null) {
            if (access.getLevel() instanceof ServerLevel level) ResourceRuntime.releaseNativeOutput(level.getServer(), this);
            return;
        }
        if (!(host.getLevel() instanceof ServerLevel level)) return;
        long now = level.getGameTime();
        if (now < nextAttempt) return;
        var account = access.account();
        var balances = account == null ? Map.<ResourceKey, Long>of() : account.available();
        if (observed != balances) {
            if (observed == null || !observed.keySet().equals(balances.keySet())) {
                var rows = new ArrayList<ResourceKey>();
                for (var key : balances.keySet()) {
                    var type = ResourceNativeType.of(key);
                    if (type != null && type != ResourceNativeType.AIR) rows.add(key);
                }
                keys = List.copyOf(rows);
                faces.keySet().retainAll(rows);
            }
            observed = balances;
        }
        if (keys.isEmpty()) {
            ResourceRuntime.releaseNativeOutput(level.getServer(), this);
            nextAttempt = now + 4;
            return;
        }
        var state = ResourceHiveHost.existing(host);
        ResourceKey chosen = null;
        for (int i = 0; i < Math.min(32, keys.size()); i++) {
            var key = keys.get(Math.floorMod(cursor++, keys.size()));
            var type = ResourceNativeType.of(key);
            if (state.nativeEject(host, type) && state.nativeMask(host, type) != 0 && account.canExtract(access.reference(), key)) {
                chosen = key;
                break;
            }
        }
        if (chosen == null) {
            ResourceRuntime.releaseNativeOutput(level.getServer(), this);
            nextAttempt = now + 4;
            return;
        }
        if (!ResourceRuntime.claimNativeOutput(level.getServer(), this)) return;
        var chosenType = ResourceNativeType.of(chosen);
        int face = faces.getOrDefault(chosen, 0);
        for (int i = 0; i < SIDES.length && !stateOutput(host, chosenType, SIDES[face]); i++) {
            face = (face + 1) % SIDES.length;
        }
        faces.put(chosen, (face + 1) % SIDES.length);
        var side = SIDES[face];
        if (chosenType == ResourceNativeType.SOUL
                && false) {
            nextAttempt = now + 1;
            return;
        }
        try {
            long accepted = transfer(host, chosen, side);
            nextAttempt = now + (accepted > 0 ? 1 : 4);
            reportedFailure = false;
        } catch (RuntimeException | LinkageError failure) {
            nextAttempt = now + 40;
            if (!reportedFailure) {
                reportedFailure = true;
                ProductiveBeesGenesis.LOGGER.error("扩展资源原生输出暂不可用，位置 {}，资源 {}；保留资产", host.getBlockPos(), chosen, failure);
            }
        }
    }
    public static boolean canReceive(BlockEntity host, ResourceKey key, Direction side) {
        var type = ResourceNativeType.of(key);
        var state = ResourceHiveHost.existing(host);
        if (type == null || state == null || !state.nativeOutput(host, type, side)
                || !(host.getLevel() instanceof ServerLevel level) || !ResourceRuntime.available(level.getServer(), key)) return false;
        if (type == ResourceNativeType.AIR && !state.nativeConfig().eject(type)) return false;
        var neighbor = host.getBlockPos().relative(side);
        if (!level.getChunkSource().hasChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4)) return false;
        if (type == ResourceNativeType.SOUL
                && false) return true;
        var receiver = receiver(level, host, key, side);
        return receiver != null && checked(receiver.simulate(1), 1) > 0;
    }
    private static boolean stateOutput(BlockEntity host, ResourceNativeType type, Direction side) {
        return ResourceHiveHost.existing(host).nativeOutput(host, type, side);
    }
    public static long transfer(BlockEntity host, ResourceKey key, Direction side) {
        if (!(host.getLevel() instanceof ServerLevel level) || !level.getServer().isSameThread()) return 0;
        var type = ResourceNativeType.of(key);
        var state = ResourceHiveHost.existing(host);
        if (type == null || state == null || !state.nativeOutput(host, type, side)
                || !ResourceRuntime.available(level.getServer(), key)) return 0;
        var account = state.account(host);
        if (!account.canExtract(state.reference(), key)) return 0;
        long maximum = Math.min(ResourceTypes.transferLimit(key), account.available().getOrDefault(key, 0L));
        if (maximum <= 0) return 0;
        var receiver = receiver(level, host, key, side);
        if (receiver == null) return 0;
        long accepted = checked(receiver.simulate(maximum), maximum);
        receiver.validate();
        if (accepted == 0) return 0;
        return ResourceHostApi.transfer(host, key, accepted, offer -> {
            if (!state.nativeOutput(host, type, side)) throw new IllegalStateException("Native output side changed");
            receiver.validate();
            long sent = checked(receiver.execute(offer.amount()), offer.amount());
            receiver.validate();
            if (!state.nativeOutput(host, type, side)) throw new IllegalStateException("Native output side changed during transfer");
            return sent;
        });
    }
    public static long checked(long accepted, long maximum) {
        if (accepted < 0 || accepted > maximum) throw new IllegalStateException("Invalid native resource acceptance");
        return accepted;
    }
    private static Receiver receiver(ServerLevel level, BlockEntity host, ResourceKey key, Direction side) {
        var pos = host.getBlockPos().relative(side);
        if (!level.getChunkSource().hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        var opposite = side.getOpposite();
        var type = ResourceNativeType.of(key);
        if (type == ResourceNativeType.SOURCE) return com.ayoshiko.productivebeesgenesis.resource.ars.ResourceArsIntegration.receiver(level, pos, opposite);
        if (type == ResourceNativeType.SOUL) return com.ayoshiko.productivebeesgenesis.resource.souls.ResourceSoulIntegration.receiver(level, pos, opposite);
        if (type == ResourceNativeType.LIGHTNING) return com.ayoshiko.productivebeesgenesis.resource.lightning.ResourceLightningIntegration.receiver(level, pos, opposite, key);
        if (type == ResourceNativeType.AIR) return com.ayoshiko.productivebeesgenesis.resource.air.ResourceAirOutput.receiver(level, pos, opposite);
        if (type == ResourceNativeType.ENERGY) {
            var cap = net.minecraftforge.common.capabilities.ForgeCapabilities.ENERGY;
            var receiver = com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, cap, pos, opposite);
            if (receiver == null || !receiver.canReceive()) return null;
            return new Receiver() {
                public long simulate(long maximum) { return receiver.receiveEnergy((int) Math.min(Integer.MAX_VALUE, maximum), true); }
                public long execute(long amount) { return receiver.receiveEnergy(Math.toIntExact(amount), false); }
                public void validate() {
                    if (com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, cap, pos, opposite) != receiver) throw new IllegalStateException("FE receiver changed");
                }
            };
        }
        if (type == ResourceNativeType.CHEMICAL) {
            var chemical = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.REGISTRY.get(ResourceTypes.chemicalId(key));
            if(chemical==null || chemical.isEmptyType())return null;
            var receiver = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.receiver(level,pos,opposite,chemical);
            if(receiver==null)return null;
            return new Receiver() {
                private long insert(long amount, Action action) {
                    var offered = com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.stack(chemical, amount);
                    var remainder = receiver.insertChemical(offered, action);
                    if (remainder == null || remainder.getAmount() < 0 || remainder.getAmount() > amount
                            || !remainder.isEmpty() && remainder.getType() != chemical) {
                        throw new IllegalStateException("Invalid chemical remainder");
                    }
                    return amount - remainder.getAmount();
                }
                public long simulate(long maximum) { return insert(maximum, Action.SIMULATE); }
                public long execute(long amount) { return insert(amount, Action.EXECUTE); }
                public void validate() {
                    if (com.ayoshiko.productivebeesgenesis.port.ForgeChemicals.receiver(level,pos,opposite,chemical) != receiver) throw new IllegalStateException("Chemical receiver changed");
                }
            };
        }
        return null;
    }
}
