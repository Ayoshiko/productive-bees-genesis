package com.ayoshiko.productivebeesgenesis.resource.emc;

import com.ayoshiko.productivebeesgenesis.resource.ResourceContent;
import com.ayoshiko.productivebeesgenesis.resource.ResourceExactAmounts;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.api.capabilities.block_entity.IEmcStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.common.capabilities.Capability;
import com.ayoshiko.productivebeesgenesis.port.RegisterCapabilitiesEvent;
import net.minecraftforge.fml.ModList;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigInteger;

/** Project Expansion 的可选公共接口桥；反射仅用于避免依赖缺失时加载其类型。 */
final class ResourceEmcBigIntegerBridge {
    private static final String INTERFACE = "cool.furry.mc.forge.projectexpansion.util.IEmcStorageBigInteger";

    private static final class Api {
        final Class<?> type;
        final Method insert;
        final Capability<IEmcStorage> capability;
        @SuppressWarnings("unchecked")
        Api() {
            try {
                type = Class.forName(INTERFACE);
                insert = type.getMethod("insertEmcBigInteger", BigInteger.class, IEmcStorage.EmcAction.class);
                capability=PECapabilities.EMC_STORAGE_CAPABILITY;
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Project Expansion BigInteger EMC API unavailable", failure);
            }
        }
    }

    private static final class Holder {
        static final Api API = new Api();
    }

    private ResourceEmcBigIntegerBridge() {}

    static void register(RegisterCapabilitiesEvent event) {
        if (ModList.get().isLoaded("projectexpansion")) {
            ResourceContent.registerHostCapability(event, Holder.API.capability,
                    (output, side) -> (IEmcStorage) output.emcCapability());
        }
    }

    static IEmcStorage receiver(ServerLevel level, BlockPos pos, Direction side) {
        if (ModList.get().isLoaded("projectexpansion")) {
            var receiver = com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, Holder.API.capability, pos, side);
            if (receiver != null) return receiver;
        }
        return com.ayoshiko.productivebeesgenesis.port.ForgeBlockCapabilities.get(level, PECapabilities.EMC_STORAGE_CAPABILITY, pos, side);
    }

    static boolean accepts(IEmcStorage receiver) {
        return ModList.get().isLoaded("projectexpansion") && Holder.API.type.isInstance(receiver);
    }

    static BigInteger insert(IEmcStorage receiver, BigInteger amount, IEmcStorage.EmcAction action) {
        if (!accepts(receiver)) return BigInteger.valueOf(receiver.insertEmc(amount.longValueExact(), action));
        try {
            return (BigInteger) Holder.API.insert.invoke(receiver, amount, action);
        } catch (InvocationTargetException failure) {
            var cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("BigInteger EMC receiver failed", cause);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("BigInteger EMC invocation failed", failure);
        }
    }

    static IEmcStorage capability(ResourceEmcOutput source) {
        if (!ModList.get().isLoaded("projectexpansion")) return source;
        return (IEmcStorage) Proxy.newProxyInstance(Holder.API.type.getClassLoader(), new Class<?>[]{Holder.API.type},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getStoredEmcBigInteger" -> source.storedExact();
                    case "getStoredEmc" -> source.getStoredEmc();
                    case "getMaximumEmc" -> source.getMaximumEmc();
                    case "getNeededEmc" -> 0L;
                    case "insertEmc" -> 0L;
                    case "extractEmc" -> source.extractEmc((long) arguments[0], (IEmcStorage.EmcAction) arguments[1]);
                    case "getMaximumEmcBigInteger" -> ResourceExactAmounts.MAX_VALUE;
                    case "getNeededEmcBigInteger", "insertEmcBigInteger" -> BigInteger.ZERO;
                    case "hasMaxedEmcBigInteger", "hasMaxedEmc" -> false;
                    case "extractEmcBigInteger" -> source.extractExact((BigInteger) arguments[0], (IEmcStorage.EmcAction) arguments[1]);
                    case "equals" -> proxy == arguments[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "toString" -> "Genesis EMC output";
                    default -> {
                        try { yield method.invoke(source, arguments); }
                        catch (InvocationTargetException failure) { throw failure.getCause(); }
                    }
                });
    }
}
