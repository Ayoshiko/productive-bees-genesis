package com.ayoshiko.productivebeesgenesis.resource;

import com.ayoshiko.productivebeesgenesis.resource.emc.ResourceEmcPlayerOutput;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import moze_intel.projecte.api.capabilities.IKnowledgeProvider;
import moze_intel.projecte.api.capabilities.PECapabilities;
import moze_intel.projecte.utils.PlayerHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import static com.ayoshiko.productivebeesgenesis.resource.ResourceAccountsMinecraftTest.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ResourceEmcPlayerOutputMinecraftTest {
    private static final BigInteger LARGE = new BigInteger("47958864426752000000");

    private static final class Fixture implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final BlockEntity host = mock(BlockEntity.class);
        final ResourceHiveHost state = mock(ResourceHiveHost.class);
        final ResourceOutputAccess port = mock(ResourceOutputAccess.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final PlayerList players = mock(PlayerList.class);
        final IKnowledgeProvider knowledge = mock(IKnowledgeProvider.class);
        final AtomicReference<BigInteger> balance = new AtomicReference<>(BigInteger.valueOf(19));
        final ResourceAccount account = account(new ResourceAccounts(() -> {}));
        final MockedStatic<ResourceHiveHost> hosts = mockStatic(ResourceHiveHost.class);
        final MockedStatic<ResourceRuntime> runtime = mockStatic(ResourceRuntime.class);

        Fixture() {
            when(server.isSameThread()).thenReturn(true);
            when(server.getPlayerList()).thenReturn(players);
            when(players.getPlayer(OWNER)).thenReturn(player);
            when(players.getPlayers()).thenReturn(List.of(player));
            when(player.getUUID()).thenReturn(OWNER);
            when(host.getLevel()).thenReturn(level);
            when(host.getBlockPos()).thenReturn(BlockPos.ZERO);
            when(level.getServer()).thenReturn(server);
            var chunks = mock(ServerChunkCache.class);
            when(level.getChunkSource()).thenReturn(chunks);
            when(chunks.hasChunk(0, 0)).thenReturn(true);
            when(level.getBlockEntity(BlockPos.ZERO)).thenReturn(host);
            when(port.sourceHost()).thenReturn(host);
            when(state.reference()).thenReturn(account.reference());
            when(state.account(host)).thenReturn(account);
            when(state.emcRecipient(host)).thenReturn(OWNER);
            hosts.when(() -> ResourceHiveHost.existing(host)).thenReturn(state);
            hosts.when(() -> ResourceHiveHost.supports(host)).thenReturn(true);
            hosts.when(() -> ResourceHiveHost.ready(host)).thenReturn(true);
            runtime.when(() -> ResourceRuntime.available(server, ResourceTypes.EMC)).thenReturn(true);
            when(knowledge.getEmc()).thenAnswer(call -> balance.get());
            doAnswer(call -> { balance.set(call.getArgument(0)); return null; }).when(knowledge).setEmc(any());
            // 与实际 ProjectE 一样，每次查询返回新包装，但共享玩家附件余额。
            when(player.getCapability(PECapabilities.KNOWLEDGE_CAPABILITY)).thenAnswer(call -> {
                var wrapper = mock(IKnowledgeProvider.class);
                when(wrapper.getEmc()).thenAnswer(read -> balance.get());
                doAnswer(write -> { knowledge.setEmc(write.getArgument(0)); return null; }).when(wrapper).setEmc(any());
                doAnswer(sync -> { knowledge.syncEmc(player); return null; }).when(wrapper).syncEmc(player);
                return wrapper;
            });
            var ticket = account.reserve(account.reference(), 1, new CompoundTag(), Map.of(ResourceTypes.EMC, LARGE));
            account.start(ticket);
            account.freeze(ticket, Map.of(ResourceTypes.EMC, LARGE));
            account.commit(ticket);
        }

        public void close() {
            ResourceEmcPlayerOutput.clear(server);
            runtime.close();
            hosts.close();
        }
    }

    @Test void freshProjectEWrappersCreditExactlyBeyondLongWithoutReplay() {
        try (var f = new Fixture()) {
            assertEquals(LARGE, ResourceEmcPlayerOutput.transfer(f.port, LARGE));
            assertEquals(LARGE.add(BigInteger.valueOf(19)), f.balance.get());
            assertTrue(f.account.availableExact().isEmpty());
            assertEquals(BigInteger.ZERO, ResourceEmcPlayerOutput.transfer(f.port, LARGE));
            verify(f.knowledge, times(1)).setEmc(any());
        }
    }

    @Test void offlineRecipientRetainsAvailableBalanceAndCreatesNoTransfer() {
        try (var f = new Fixture()) {
            when(f.players.getPlayer(OWNER)).thenReturn(null);
            assertEquals(ResourceEmcPlayerOutput.State.OFFLINE, ResourceEmcPlayerOutput.state(f.host));
            assertEquals(BigInteger.ZERO, ResourceEmcPlayerOutput.transfer(f.port, LARGE));
            assertEquals(LARGE, f.account.availableExact().get(ResourceTypes.EMC));
            assertNull(f.account.transferTicket());
            verify(f.knowledge, never()).setEmc(any());
        }
    }

    @Test void writeThenThrowQuarantinesUnknownResultAndCannotCreditAgain() {
        try (var f = new Fixture()) {
            doAnswer(call -> {
                f.balance.set(call.getArgument(0));
                throw new IllegalStateException("Injected failure after player attachment write");
            }).when(f.knowledge).setEmc(any());
            assertThrows(IllegalStateException.class, () -> ResourceEmcPlayerOutput.transfer(f.port, LARGE));
            assertEquals(LARGE, f.account.uncertainTransferExact().get(ResourceTypes.EMC));
            assertEquals(BigInteger.ZERO, ResourceEmcPlayerOutput.transfer(f.port, LARGE));
            verify(f.knowledge, times(1)).setEmc(any());
        }
    }

    @Test void failedDisplaySyncRetriesNotificationWithoutAddingEmc() {
        try (var f = new Fixture(); var scores = mockStatic(PlayerHelper.class)) {
            ResourceEmcPlayerOutput.transfer(f.port, LARGE);
            doThrow(new IllegalStateException("Injected packet failure")).doNothing().when(f.knowledge).syncEmc(f.player);
            when(f.server.getTickCount()).thenReturn(10);
            ResourceEmcPlayerOutput.sync(f.server);
            when(f.server.getTickCount()).thenReturn(20);
            ResourceEmcPlayerOutput.sync(f.server);
            when(f.server.getTickCount()).thenReturn(110);
            ResourceEmcPlayerOutput.sync(f.server);
            verify(f.knowledge, times(2)).syncEmc(f.player);
            verify(f.knowledge, times(1)).setEmc(any());
            assertEquals(LARGE.add(BigInteger.valueOf(19)), f.balance.get());
        }
    }
}
