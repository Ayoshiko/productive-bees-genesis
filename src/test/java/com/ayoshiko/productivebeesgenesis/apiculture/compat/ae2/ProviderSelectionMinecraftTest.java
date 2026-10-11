package com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2;

import appeng.api.crafting.PatternDetailsHelper;
import appeng.api.ids.AEComponents;
import appeng.core.definitions.AEItems;
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
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.material.Fluids;
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
        MeTerminalView amount(MeTerminalRequest.Action action, int row, long amount) {
            tick += 5;
            return view = backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++sequence, action, view.revision(), row, view.page(), amount, ""));
        }
        void scale(int scope, MeTerminalRequest.Action action, long factor) {
            carried = pattern();
            if (scope == 2) selectAcrossPages();
            else { browse(); if (scope == 1) send(PROVIDER_OPEN, 1); }
            assertEquals(MeTerminalView.Mode.PROVIDER_EDIT_SOURCE, send(scope == 2 ? PROVIDER_EDIT_SELECTED : PROVIDER_EDIT_OPEN, -1).mode());
            assertEquals(Status.WAITING, amount(action, -1, factor).status());
        }
        void advance() {
            for (int i = 0; i < 400 && view.status() == Status.WAITING; i++) send(POLL, -1);
        }
        void ready() { advance(); assertEquals(Status.OK, view.status()); }
        void selectAll() { assertTrue(amount(PROVIDER_EDIT_SELECT, -1, 1).confirm()); }
        ItemStack item(int provider, int slot) { return logics.get(provider).getPatternInv().getStackInSlot(slot); }
        void put(int provider, int slot, ItemStack item) { logics.get(provider).getPatternInv().setItemDirect(slot, item.copy()); }
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


    private static ItemStack pattern(long input, long output) {
        return PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), input)),
                List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), output)));
    }
    private static void scaled(ItemStack before, ItemStack actual, long factor, boolean divide) {
        var original = before.get(AEComponents.ENCODED_PROCESSING_PATTERN); var result = actual.get(AEComponents.ENCODED_PROCESSING_PATTERN);
        for (int side = 0; side < 2; side++) {
            var a = side == 0 ? original.sparseInputs() : original.sparseOutputs();
            var b = side == 0 ? result.sparseInputs() : result.sparseOutputs(); assertEquals(a.size(), b.size());
            for (int slot = 0; slot < a.size(); slot++) {
                if (a.get(slot) == null) { assertNull(b.get(slot)); continue; }
                assertEquals(a.get(slot).what(), b.get(slot).what());
                assertEquals(divide ? a.get(slot).amount() / factor : a.get(slot).amount() * factor, b.get(slot).amount());
            }
        }
        var restored = actual.copy(); restored.set(AEComponents.ENCODED_PROCESSING_PATTERN, original);
        assertTrue(ItemStack.matches(before, restored), "Preserve count and all other components");
    }
    @Test void providerScalingUsesEachScopeAndNeedsNoSampleOrDivisibleCarriedPattern() throws Exception {
        for (int scope = 0; scope < 3; scope++) for (boolean divide : new boolean[]{false, true})
                try (var r = new Rig(10, 36); var samples = mockStatic(TerminalPatternSample.class)) {
            samples.when(() -> TerminalPatternSample.capture(r.player, r.menu)).thenThrow(new AssertionError("Scaling must not capture a material sample"));
            var named = new ItemStack(Items.RAW_IRON); named.set(DataComponents.CUSTOM_NAME, Component.literal("精确键"));
            var original = PatternDetailsHelper.encodeProcessingPattern(Arrays.asList(new GenericStack(AEItemKey.of(named), 6), null,
                    new GenericStack(AEFluidKey.of(Fluids.WATER), 1000)), List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 4)));
            original.set(DataComponents.CUSTOM_NAME, Component.literal("供应器倍率"));
            for (int provider = 0; provider < 10; provider++) r.put(provider, 0, original);
            r.scale(scope, divide ? PROVIDER_EDIT_DIVIDE : PROVIDER_EDIT_MULTIPLY, 2); r.ready(); r.selectAll();
            int count = scope == 0 ? 8 : scope == 1 ? 1 : 2; assertEquals(count, r.view.bytes());
            assertEquals(Status.WAITING, r.send(PROVIDER_EDIT_APPLY, -1).status()); r.advance();
            assertEquals(Status.PROVIDER_EDIT_DONE, r.view.status()); assertEquals(Integer.toString(count), r.view.cpu());
            for (int provider = 0; provider < 10; provider++) {
                boolean chosen = scope == 0 ? provider < 8 : scope == 1 ? provider == 1 : provider == 0 || provider == 8;
                if (chosen) scaled(original, r.item(provider, 0), 2, divide); else assertTrue(ItemStack.matches(original, r.item(provider, 0)));
            }
            assertTrue(ItemStack.matches(pattern(), r.carried)); samples.verifyNoInteractions();
        }
    }
    @Test void scaledSlotSelectionSurvivesPagesAndDetailsAndDoesNotSelectOtherPatterns() throws Exception {
        try (var r = new Rig(1, 36)) {
            for (int slot = 0; slot < 10; slot++) r.put(0, slot, pattern(2 * (slot + 1), 2));
            r.scale(0, PROVIDER_EDIT_MULTIPLY, 4); r.ready(); r.send(PROVIDER_EDIT_TOGGLE, 0); r.send(PROVIDER_EDIT_DETAILS, 0);
            assertEquals(MeTerminalView.Mode.PROVIDER_EDIT_DETAIL, r.view.mode());
            assertEquals(2, r.view.rows().getFirst().amount()); assertEquals(8, r.view.rows().getFirst().extra()); assertTrue(r.view.rows().getFirst().enabled());
            r.send(PROVIDER_EDIT_LIST, -1); r.send(PAGE, -1, 1, ""); r.send(PROVIDER_EDIT_TOGGLE, 0);
            assertEquals(2, r.view.bytes()); r.send(PROVIDER_EDIT_APPLY, -1); r.advance(); assertEquals(Status.PROVIDER_EDIT_DONE, r.view.status());
            for (int slot = 0; slot < 10; slot++) {
                var before = pattern(2 * (slot + 1), 2);
                if (slot == 0 || slot == 8) scaled(before, r.item(0, slot), 4, false); else assertTrue(ItemStack.matches(before, r.item(0, slot)));
            }
        }
    }
    @Test void divisionShowsIneligibleCandidatesAndCanStillApplyTheEligibleOnes() throws Exception {
        try (var r = new Rig(1, 9)) {
            r.put(0, 0, pattern(6, 4)); r.put(0, 1, pattern(3, 2)); r.put(0, 2, AEItems.PROCESSING_PATTERN.stack());
            r.scale(0, PROVIDER_EDIT_DIVIDE, 2); r.ready(); assertEquals(3, r.view.rows().size());
            assertEquals(Status.PATTERN_NOT_DIVISIBLE.ordinal(), r.view.rows().get(1).extra());
            assertEquals(Status.PATTERN_INVALID.ordinal(), r.view.rows().get(2).extra());
            r.send(PROVIDER_EDIT_TOGGLE, 1); assertEquals(0, r.view.bytes());
            r.selectAll(); assertEquals(1, r.view.bytes()); r.send(PROVIDER_EDIT_APPLY, -1); r.advance();
            assertEquals(Status.PROVIDER_EDIT_DONE, r.view.status()); scaled(pattern(6, 4), r.item(0, 0), 2, true);
            assertTrue(ItemStack.matches(pattern(3, 2), r.item(0, 1))); assertTrue(ItemStack.matches(AEItems.PROCESSING_PATTERN.stack(), r.item(0, 2)));
        }
    }
    @Test void providerScalingRefusesIndividualAndCombinedOverflowAndFactorOne() throws Exception {
        for (boolean unchanged : new boolean[]{false, true}) try (var r = new Rig(1, 9)) {
            r.put(0, 0, pattern(Long.MAX_VALUE / 2 + 1, 1));
            long amount = Long.MAX_VALUE / 4 + 1;
            r.put(0, 1, PatternDetailsHelper.encodeProcessingPattern(List.of(new GenericStack(AEItemKey.of(Items.RAW_IRON), amount),
                    new GenericStack(AEItemKey.of(Items.RAW_IRON), amount)), List.of(new GenericStack(AEItemKey.of(Items.IRON_INGOT), 1))));
            r.scale(0, PROVIDER_EDIT_MULTIPLY, unchanged ? 1 : 2); r.ready();
            assertTrue(r.view.rows().stream().allMatch(row -> row.extra() == (unchanged ? Status.PATTERN_NO_CHANGE : Status.PATTERN_OVERFLOW).ordinal()));
            r.amount(PROVIDER_EDIT_SELECT, -1, 1); assertEquals(0, r.view.bytes()); assertFalse(r.view.confirm());
            assertEquals(Status.INVALID, r.send(PROVIDER_EDIT_APPLY, -1).status()); assertEquals(Long.MAX_VALUE / 2 + 1, r.item(0, 0).get(AEComponents.ENCODED_PROCESSING_PATTERN).sparseInputs().getFirst().amount());
        }
        try (var r = new Rig(1, 9)) {
            var original = pattern(1, 1); r.put(0, 0, original); r.scale(0, PROVIDER_EDIT_MULTIPLY, Long.MAX_VALUE); r.ready(); r.selectAll();
            r.send(PROVIDER_EDIT_APPLY, -1); r.advance(); assertEquals(Status.PROVIDER_EDIT_DONE, r.view.status()); scaled(original, r.item(0, 0), Long.MAX_VALUE, false);
        }
    }
    @Test void malformedScaleRequestsCannotChangeTheFrozenOperationOrSkipConfirmation() throws Exception {
        for (int failure = 0; failure < 5; failure++) try (var r = new Rig(1, 9)) {
            r.put(0, 0, pattern(4, 2)); r.carried = pattern(); r.browse(); r.send(PROVIDER_EDIT_OPEN, -1); r.tick += 5;
            r.view = r.backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++r.sequence, PROVIDER_EDIT_MULTIPLY,
                    failure == 0 ? r.view.revision() + 1 : r.view.revision(), failure == 1 ? 0 : -1,
                    failure == 2 ? 1 : 0, failure == 3 ? 0 : 2, failure == 4 ? "unexpected" : ""));
            assertEquals(failure == 0 || failure == 2 ? Status.STALE : Status.INVALID, r.view.status());
            assertTrue(ItemStack.matches(pattern(4, 2), r.item(0, 0)));
        }
        try (var r = new Rig(1, 9)) {
            var original = pattern(4, 2); r.put(0, 0, original); r.scale(0, PROVIDER_EDIT_MULTIPLY, 2);
            assertEquals(Status.INVALID, r.amount(PROVIDER_EDIT_DIVIDE, -1, 2).status()); r.send(POLL, -1); r.ready();
            assertEquals(Status.INVALID, r.amount(PROVIDER_EDIT_MULTIPLY, -1, 10).status());
            r.selectAll(); r.send(PROVIDER_EDIT_APPLY, -1); r.advance(); assertEquals(Status.PROVIDER_EDIT_DONE, r.view.status());
            scaled(original, r.item(0, 0), 2, false);
            r.send(PROVIDER_EDIT_APPLY, -1); r.send(POLL, -1); scaled(original, r.item(0, 0), 2, false);
        }
    }
    @Test void changedSlotsTargetsPermissionsAndCarriedPatternsRejectTheConfirmedBatch() throws Exception {
        for (int failure = 0; failure < 9; failure++) try (var r = new Rig(1, 9)) {
            var original = pattern(4, 2); r.put(0, 0, original); r.scale(0, PROVIDER_EDIT_MULTIPLY, 2); r.ready(); r.selectAll();
            switch (failure) {
                case 0 -> r.put(0, 0, pattern(8, 2));
                case 1 -> when(r.hosts.getFirst().getLogic()).thenReturn(mock(PatternProviderLogic.class));
                case 2 -> when(r.nodes.getFirst().isActive()).thenReturn(false);
                case 3 -> when(r.hosts.getFirst().isVisibleInTerminal()).thenReturn(false);
                case 4 -> r.blocks.clear();
                case 5 -> when(r.level.mayInteract(eq(r.player), any())).thenReturn(false);
                case 6 -> r.carried = pattern(8, 4);
                case 7 -> when(r.menu.stillValid(r.player)).thenReturn(false);
                case 8 -> r.tick += 601;
            }
            var before = r.item(0, 0).copy(); clearInvocations(r.hosts.getFirst());
            assertEquals(Status.STALE, r.send(PROVIDER_EDIT_APPLY, -1).status(), "failure=" + failure);
            assertTrue(ItemStack.matches(before, r.item(0, 0))); verify(r.hosts.getFirst(), never()).saveChanges();
        }
    }
    @Test void cancellationAndChangedNextSlotKeepCompletedScaleWithoutRepeatingIt() throws Exception {
        for (boolean cancelled : new boolean[]{false, true}) try (var r = new Rig(1, 9)) {
            var original = pattern(4, 2); r.put(0, 0, original); r.put(0, 1, original);
            r.scale(0, PROVIDER_EDIT_MULTIPLY, 2); r.ready(); r.selectAll(); r.send(PROVIDER_EDIT_APPLY, -1); r.send(POLL, -1);
            scaled(original, r.item(0, 0), 2, false); assertTrue(ItemStack.matches(original, r.item(0, 1)));
            if (cancelled) r.send(PROVIDER_EDIT_CANCEL, -1); else { r.put(0, 1, pattern(8, 2)); r.send(POLL, -1); }
            assertEquals(Status.PROVIDER_EDIT_STOPPED, r.view.status()); assertEquals("1", r.view.cpu());
            var second = r.item(0, 1).copy(); r.send(POLL, -1); r.send(PAGE, -1, 0, ""); r.send(PROVIDER_EDIT_APPLY, -1);
            scaled(original, r.item(0, 0), 2, false); assertTrue(ItemStack.matches(second, r.item(0, 1)));
        }
    }
    @Test void unknownScaleWriteAndReentrantCloseStopOtherProvidersWithoutCompensation() throws Exception {
        for (boolean reentrant : new boolean[]{false, true}) try (var r = new Rig(10, 36)) {
            var original = pattern(4, 2); r.put(0, 0, original); r.put(8, 0, original);
            r.scale(2, PROVIDER_EDIT_MULTIPLY, 2); r.ready(); r.selectAll(); clearInvocations(r.hosts.get(0));
            if (reentrant) doAnswer(call -> { r.backend.close(); return null; }).when(r.hosts.get(0)).saveChanges();
            else doThrow(new IllegalStateException("Unknown save after scaled slot write")).when(r.hosts.get(0)).saveChanges();
            r.send(PROVIDER_EDIT_APPLY, -1); r.send(POLL, -1);
            assertEquals(Status.PROVIDER_PATTERN_UNKNOWN, r.view.status()); assertEquals("0", r.view.cpu());
            scaled(original, r.item(0, 0), 2, false); assertTrue(ItemStack.matches(original, r.item(8, 0)));
            // 一次原生写入可能触发两个保存回调；重放后不能再触发保存或改写。
            verify(r.hosts.get(0), atLeastOnce()).saveChanges(); clearInvocations(r.hosts.get(0));
            r.send(POLL, -1); r.send(PROVIDER_EDIT_APPLY, -1);
            scaled(original, r.item(0, 0), 2, false); assertTrue(ItemStack.matches(original, r.item(8, 0))); verify(r.hosts.get(0), never()).saveChanges();
        }
    }
    @Test void sharedBudgetDefersScalePreparationAndExecutionWithoutMovingTheSlot() throws Exception {
        try (var r = new Rig(1, 9)) {
            var original = pattern(4, 2); r.put(0, 0, original); r.scale(0, PROVIDER_EDIT_MULTIPLY, 2);
            r.tick += 5; assertTrue(MeTerminalBudget.expensive(r.server));
            r.view = r.backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++r.sequence, POLL, r.view.revision(), -1, 0, 0, ""));
            assertEquals(Status.WAITING, r.view.status()); assertTrue(r.view.rows().isEmpty()); r.ready(); r.selectAll(); r.send(PROVIDER_EDIT_APPLY, -1);
            r.tick += 5; assertTrue(MeTerminalBudget.expensive(r.server)); clearInvocations(r.hosts.getFirst());
            r.view = r.backend.request(new MeTerminalRequest(1, UUID.randomUUID(), ++r.sequence, POLL, r.view.revision(), -1, 0, 0, ""));
            assertTrue(ItemStack.matches(original, r.item(0, 0))); verify(r.hosts.getFirst(), never()).saveChanges();
            r.advance(); assertEquals(Status.PROVIDER_EDIT_DONE, r.view.status()); scaled(original, r.item(0, 0), 2, false);
        }
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
