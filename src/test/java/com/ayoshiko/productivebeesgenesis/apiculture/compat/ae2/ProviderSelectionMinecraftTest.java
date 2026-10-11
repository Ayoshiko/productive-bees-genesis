package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.networking.*;
import appeng.api.stacks.*;
import appeng.blockentity.crafting.PatternProviderBlockEntity;
import appeng.helpers.patternprovider.PatternProviderLogic;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import com.ayoshiko.productivebeesgenesis.apiculture.me.*;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.Status;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class ProviderSelectionMinecraftTest {
    private static final class Rig implements AutoCloseable {
        final MinecraftServer server = mock(MinecraftServer.class);
        final ServerLevel level = mock(ServerLevel.class);
        final ServerPlayer player = mock(ServerPlayer.class);
        final AbstractContainerMenu menu = mock(AbstractContainerMenu.class);
        final IGrid grid = mock(IGrid.class);
        final List<IGridNode> nodes = new ArrayList<>();
        final List<PatternProviderBlockEntity> hosts = new ArrayList<>();
        final List<PatternProviderLogic> logics = new ArrayList<>();
        final Map<BlockPos, PatternProviderBlockEntity> blocks = new LinkedHashMap<>();
        final AeMePatternProviders backend;
        MeTerminalView view = MeTerminalView.empty(Status.CLOSED);
        ItemStack carried = ItemStack.EMPTY;
        long tick, sequence;
        boolean connected = true;
        Rig(int providers, int size) throws Exception {
            var field = ServerPlayer.class.getField("server"); field.setAccessible(true); field.set(player, server);
            when(server.isSameThread()).thenReturn(true); when(server.overworld()).thenReturn(level);
            when(server.getTickCount()).thenAnswer(call -> (int) tick);
            when(level.getGameTime()).thenAnswer(call -> tick); when(level.getServer()).thenReturn(server);
            when(level.dimension()).thenReturn(Level.OVERWORLD);
            when(player.level()).thenReturn(level); when(player.serverLevel()).thenReturn(level);
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.registryAccess()).thenReturn(RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY));
            when(player.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(new TerminalCursor());
            when(menu.stillValid(player)).thenReturn(true); when(menu.getCarried()).thenAnswer(call -> carried);
            player.containerMenu = menu;
            when(level.mayInteract(eq(player), any())).thenReturn(true);
            when(level.hasChunkAt(any())).thenAnswer(call -> blocks.containsKey(call.getArgument(0)));
            when(level.getBlockEntity(any())).thenAnswer(call -> blocks.get(call.getArgument(0)));
            when(grid.getNodes()).thenAnswer(call -> nodes);
            for (int i = 0; i < providers; i++) {
                var pos = new BlockPos(i * 4, 64, 0);
                var host = mock(PatternProviderBlockEntity.class); var node = mock(IGridNode.class);
                when(host.getBlockEntity()).thenReturn(host); when(host.getLevel()).thenReturn(level);
                when(host.getBlockPos()).thenReturn(pos); when(host.getBlockState()).thenReturn(Blocks.CHEST.defaultBlockState());
                when(host.getActionableNode()).thenReturn(node); when(host.getGrid()).thenReturn(grid);
                when(host.isVisibleInTerminal()).thenReturn(true);
                when(host.getTerminalIcon()).thenReturn(AEItemKey.of(Items.CHEST));
                when(host.getTargets()).thenAnswer(call -> EnumSet.of(Direction.NORTH));
                when(node.getOwner()).thenReturn(host); when(node.getLevel()).thenReturn(level);
                when(node.getGrid()).thenReturn(grid); when(node.isActive()).thenReturn(true);
                var logic = new PatternProviderLogic(mock(IManagedGridNode.class, RETURNS_SELF), host, size);
                when(host.getLogic()).thenReturn(logic); when(host.getTerminalPatternInventory()).thenReturn(logic.getPatternInv());
                hosts.add(host); nodes.add(node); logics.add(logic); blocks.put(pos, host);
            }
            backend = new AeMePatternProviders(player, grid, () -> connected);
        }
        MeTerminalView send(MeTerminalRequest.Action action, int row, int page, String query) {
            tick += 5;
            return view = backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++sequence, action, view.revision(), row, page, 0, query));
        }
        MeTerminalView send(MeTerminalRequest.Action action, int row) { return send(action, row, view.page(), ""); }
        void browse() { assertEquals(Status.OK, send(PROVIDERS, -1, 0, "").status()); }
        void selectAcrossPages() {
            browse(); assertEquals(Status.OK, send(PROVIDER_SELECT, 0).status());
            send(PAGE, -1, 1, ""); assertEquals(Status.OK, send(PROVIDER_SELECT, 0).status()); assertEquals(2, view.bytes());
        }
        @Override public void close() { backend.close(); MeTerminalBudget.stopped(new ServerStoppedEvent(server)); }
    }
    private static ItemStack pattern() {
        return PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), 2)),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1)));
    }

    @Test void selectionsSurvivePagingAndSameSearchWithoutIncludingOtherVisibleProviders() throws Exception {
        try (var r = new Rig(10, 36)) {
            r.selectAcrossPages(); assertEquals("72", r.view.cpu()); assertEquals(1, r.view.rows().getFirst().extra());
            r.send(PAGE, -1, 0, "");
            assertEquals(2, r.view.bytes()); assertEquals(1, r.view.rows().getFirst().extra()); assertEquals(0, r.view.rows().get(1).extra());
            r.send(PROVIDERS, -1, 0, ""); assertEquals(2, r.view.bytes());
            r.send(PROVIDER_OPEN, 0); assertEquals(MeTerminalView.Mode.PROVIDER_SLOTS, r.view.mode());
            r.send(PROVIDERS, -1, 0, ""); assertEquals(2, r.view.bytes());
            r.carried = pattern(); assertEquals(Status.INVALID, r.send(PROVIDER_EDIT_OPEN, -1).status());
            var source = r.send(PROVIDER_EDIT_SELECTED, -1);
            assertEquals(MeTerminalView.Mode.PROVIDER_EDIT_SOURCE, source.mode()); assertEquals(2, source.bytes()); assertEquals("72", source.cpu());
            assertTrue(r.logics.stream().allMatch(logic -> logic.getAvailablePatterns().isEmpty()));
            r.send(PROVIDERS, -1, 0, ""); assertEquals(0, r.view.bytes());
        }
    }
    @Test void providerCountSlotLimitAndDuplicateSelectionNeverExpandTheScope() throws Exception {
        try (var r = new Rig(10, 36)) {
            r.browse();
            for (int i = 0; i < 8; i++) assertEquals(Status.OK, r.send(PROVIDER_SELECT, i).status());
            assertEquals(8, r.view.bytes()); assertEquals("288", r.view.cpu()); r.send(PAGE, -1, 1, "");
            assertEquals(Status.TOO_LARGE, r.send(PROVIDER_SELECT, 0).status()); assertEquals(8, r.view.bytes());
            r.send(PAGE, -1, 0, ""); r.send(PROVIDER_SELECT, 0); assertEquals(7, r.view.bytes());
            r.send(PROVIDER_SELECT, 0); assertEquals(8, r.view.bytes()); r.send(PROVIDER_SELECTION_CLEAR, -1); assertEquals(0, r.view.bytes());
        }
        try (var r = new Rig(1, 289)) {
            r.browse(); assertEquals(Status.TOO_LARGE, r.send(PROVIDER_SELECT, 0).status()); assertEquals(0, r.view.bytes());
        }
    }
    @Test void changingQueryClosingDisconnectingAndTimeoutReleaseAllSelectedIdentities() throws Exception {
        for (int mode = 0; mode < 5; mode++) try (var r = new Rig(10, 9)) {
            r.selectAcrossPages();
            switch (mode) {
                case 0 -> r.send(PROVIDERS, -1, 0, "no-match");
                case 1 -> { r.backend.close(); r.browse(); }
                case 2 -> { r.connected = false; assertEquals(Status.DISCONNECTED, r.send(PROVIDER_SELECT, 1).status()); }
                case 3 -> { r.tick = 610; r.backend.expire(); assertEquals(Status.STALE, r.send(PROVIDER_EDIT_SELECTED, -1).status()); }
                case 4 -> { r.tick = 400; r.send(PAGE, -1, 0, ""); r.tick = 610; assertEquals(Status.STALE, r.send(PROVIDER_EDIT_SELECTED, -1).status()); }
            }
            assertEquals(0, r.view.bytes(), "mode=" + mode);
        }
    }
    @Test void hiddenUnloadedReplacedOrOfflineTargetsInvalidateTheEntireSelection() throws Exception {
        for (int mode = 0; mode < 5; mode++) try (var r = new Rig(10, 36)) {
            r.selectAcrossPages();
            switch (mode) {
                case 0 -> when(r.hosts.get(0).isVisibleInTerminal()).thenReturn(false);
                case 1 -> r.blocks.remove(r.hosts.get(0).getBlockPos());
                case 2 -> when(r.hosts.get(0).getLogic()).thenReturn(mock(PatternProviderLogic.class));
                case 3 -> when(r.hosts.get(0).getTerminalPatternInventory()).thenReturn(r.logics.get(1).getPatternInv());
                case 4 -> when(r.nodes.get(0).isActive()).thenReturn(false);
            }
            assertEquals(Status.STALE, r.send(PROVIDER_EDIT_SELECTED, -1).status()); assertEquals(0, r.view.bytes());
            assertTrue(r.logics.stream().allMatch(logic -> logic.getAvailablePatterns().isEmpty()));
        }
    }
    @Test void onlyDisplayedRowsWithTheCurrentPageAndRevisionCanBeSelected() throws Exception {
        try (var r = new Rig(10, 9)) {
            r.browse();
            assertEquals(Status.INVALID, r.send(PROVIDER_SELECT, 8).status()); assertEquals(0, r.view.bytes());
            assertEquals(Status.INVALID, r.send(PROVIDER_SELECT, 0, 1, "").status()); assertEquals(0, r.view.bytes());
            long oldRevision = r.view.revision(); r.send(PROVIDER_SELECT, 0); r.tick += 5;
            r.view = r.backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++r.sequence, PROVIDER_SELECT, oldRevision, 1, 0, 0, ""));
            assertEquals(Status.STALE, r.view.status()); assertEquals(0, r.view.bytes());
        }
    }
    @Test void confirmedCrossPageBatchWritesOnlyChosenProvidersThroughNativeInventories() throws Exception { exerciseBatch(false); }
    @Test void unknownNativeWriteStopsTheOtherProviderAndCannotBeReplayed() throws Exception { exerciseBatch(true); }
    private static void exerciseBatch(boolean failSave) throws Exception {
        try (var r = new Rig(10, 36); var samples = mockStatic(TerminalPatternSample.class)) {
            var original = pattern(); r.carried = original.copy();
            for (var logic : r.logics) logic.getPatternInv().setItemDirect(0, original.copy());
            var sample = mock(TerminalPatternSample.class);
            when(sample.item()).thenAnswer(call -> new ItemStack(Items.DIAMOND)); when(sample.current(r.player, r.menu)).thenReturn(true);
            when(sample.commit(eq(r.player), eq(r.menu), any())).thenAnswer(call -> {
                Supplier<TerminalCursorExchange.Result> apply = call.getArgument(2); return apply.get();
            });
            samples.when(() -> TerminalPatternSample.capture(r.player, r.menu)).thenReturn(sample);
            r.selectAcrossPages(); r.send(PROVIDER_EDIT_SELECTED, -1); r.send(PROVIDER_EDIT_REPLACE, 0);
            for (int i = 0; i < 40 && r.view.status() == Status.WAITING; i++) r.send(POLL, -1);
            assertEquals(Status.OK, r.view.status()); assertEquals(2, r.view.rows().size());
            r.view = r.backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++r.sequence, PROVIDER_EDIT_SELECT,
                    r.view.revision(), -1, r.view.page(), 1, ""));
            assertTrue(r.view.confirm());
            clearInvocations(r.hosts.get(0));
            if (failSave) doThrow(new IllegalStateException("Unknown provider save")).when(r.hosts.get(0)).saveChanges();
            r.send(PROVIDER_EDIT_APPLY, -1);
            for (int i = 0; i < 8 && r.view.status() == Status.WAITING; i++) r.send(POLL, -1);
            assertEquals(failSave ? Status.PROVIDER_PATTERN_UNKNOWN : Status.PROVIDER_EDIT_DONE, r.view.status());
            assertEquals(failSave ? "0" : "2", r.view.cpu());
            for (int i = 0; i < r.logics.size(); i++) {
                var actual = r.logics.get(i).getPatternInv().getStackInSlot(0);
                assertEquals(i != 0 && (failSave || i != 8), ItemStack.matches(original, actual), "provider=" + i);
                assertEquals(1, actual.getCount()); assertEquals(1, r.logics.get(i).getAvailablePatterns().size());
            }
            assertTrue(ItemStack.matches(original, r.carried));
            r.send(POLL, -1); r.send(POLL, -1);
            if (failSave) { assertEquals(Status.PROVIDER_PATTERN_UNKNOWN, r.view.status()); verify(r.hosts.get(0), times(1)).saveChanges(); }
        }
    }
}
