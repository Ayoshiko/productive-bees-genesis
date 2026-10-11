package com.ayoshiko.productivebeesgenesis.apiculture.me;

import appeng.api.ids.AEComponents;
import appeng.core.definitions.AEItems;
import com.ayoshiko.productivebeesgenesis.apiculture.core.*;
import io.netty.buffer.Unpooled;
import java.util.*;
import net.minecraft.core.*;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.*;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.GameRules;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalRequest.Action.*;
import static com.ayoshiko.productivebeesgenesis.apiculture.me.MeTerminalView.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@Tag("minecraft")
class CraftingPatternOptionsMinecraftTest {
	private static final class Rig implements AutoCloseable {
		final MinecraftServer server = mock(MinecraftServer.class);
		final ServerLevel level = mock(ServerLevel.class);
		final ServerPlayer player = mock(ServerPlayer.class);
		final AbstractContainerMenu menu = mock(AbstractContainerMenu.class, withSettings().extraInterfaces(TerminalCraftingMenu.Host.class));
		final RegistryAccess registries = RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
		final RecipeManager recipes = mock(RecipeManager.class);
		final CraftingRecipe recipe = mock(CraftingRecipe.class);
		final RecipeHolder<CraftingRecipe> holder = new RecipeHolder<>(ResourceLocation.parse("test:pattern_options"), recipe);
		final TerminalCursor cursor = new TerminalCursor();
		final MePatternSession session = new MePatternSession();
		final UUID id = UUID.randomUUID();
		final TerminalCraftingAccount account;
		MeTerminalView view = MeTerminalView.patternStatus(Status.CLOSED);
		ItemStack carried = AEItems.BLANK_PATTERN.stack(4);
		ItemStack output = new ItemStack(Items.IRON_BLOCK);
		long tick, sequence;
		Rig() throws Exception {
			var field = ServerPlayer.class.getField("server"); field.setAccessible(true); field.set(player, server);
			when(server.isSameThread()).thenReturn(true); when(server.overworld()).thenReturn(level);
			when(level.getGameTime()).thenAnswer(call -> tick);
			when(player.level()).thenReturn(level); when(player.serverLevel()).thenReturn(level);
			when(player.getUUID()).thenReturn(id); when(player.getInventory()).thenReturn(new Inventory(player));
			when(player.registryAccess()).thenReturn(registries); when(level.registryAccess()).thenReturn(registries);
			when(player.getData(NetworkContent.TERMINAL_CURSOR)).thenReturn(cursor);
			when(level.enabledFeatures()).thenReturn(FeatureFlags.DEFAULT_FLAGS);
			when(level.getGameRules()).thenReturn(mock(GameRules.class));
			when(level.getRecipeManager()).thenReturn(recipes);
			when(recipes.getRecipeFor(eq(RecipeType.CRAFTING), any(CraftingInput.class), eq(level))).thenReturn(Optional.of(holder));
			doReturn(Optional.of(holder)).when(recipes).byKey(holder.id());
			when(recipe.matches(any(CraftingInput.class), eq(level))).thenReturn(true);
			when(recipe.assemble(any(CraftingInput.class), eq(registries))).thenAnswer(call -> output.copy());
			when(recipe.getIngredients()).thenReturn(NonNullList.withSize(9, Ingredient.of(Items.IRON_INGOT)));
			when(recipe.getRemainingItems(any(CraftingInput.class))).thenAnswer(call ->
					NonNullList.withSize(((CraftingInput) call.getArgument(0)).size(), ItemStack.EMPTY));
			var tag = new CompoundTag(); tag.putInt("schema", 1); tag.putUUID("owner", id);
			tag.putString("dimension", "minecraft:overworld"); tag.putLong("position", 0); tag.putLong("revision", 0);
			tag.putBoolean("uncertain", false); tag.put("pending", new CompoundTag());
			var grid = new ListTag();
			for (int i = 0; i < 9; i++) grid.add(new ItemStack(Items.IRON_INGOT, 32).saveOptional(registries));
			tag.put("grid", grid); account = TerminalCraftingAccount.load(tag, registries); assertTrue(account.available());
			when(((TerminalCraftingMenu.Host) menu).craftingAccount(player)).thenReturn(account);
			when(menu.stillValid(player)).thenReturn(true); when(menu.getCarried()).thenAnswer(call -> carried);
			doAnswer(call -> { carried = ((ItemStack) call.getArgument(0)).copy(); return null; }).when(menu).setCarried(any());
			carried.set(DataComponents.CUSTOM_NAME, Component.literal("保留样板组件"));
			// Blank patterns already default to 64; a non-default component survives item transmutation.
			carried.set(DataComponents.MAX_STACK_SIZE, 32);
			field = TerminalCursor.class.getDeclaredField("stack"); field.setAccessible(true); field.set(cursor, carried.copy());
			player.containerMenu = menu;
		}
		MeTerminalView send(MeTerminalRequest.Action action, int page, long amount) {
			tick += 5;
			return view = session.request(player, new MeTerminalRequest(1, id, ++sequence, action, view.revision(), -1, page, amount, ""));
		}
		MeTerminalView encode(int options) {
			var source = TerminalPatternSource.capture(player, menu); assertNotNull(source, "Valid recipe source");
			assertTrue(source.current(player, menu), "Current recipe source");
			var plan = com.ayoshiko.productivebeesgenesis.apiculture.compat.ae2.AeCraftingPatternEncoder.prepare(player, carried, source, CraftingOptions.from(options));
			assertEquals(Status.OK, plan.status(), "Native encoding options " + options);
			var reply = send(PATTERN_ENCODE_CRAFTING, 0, options);
			assertEquals(Status.OK, reply.status(), "Session encoding options " + options); return reply;
		}
		@Override public void close() { session.close(); MeTerminalBudget.stopped(new ServerStoppedEvent(server)); }
	}

	@Test void allFourOptionsEncodeNativeComponentsOnceWithoutConsumingSamples() throws Exception {
		for (int bits = 0; bits < 4; bits++) try (var r = new Rig()) {
			var grid = r.account.state(); var before = r.carried.copy();
			assertEquals(Status.OK, r.encode(bits).status()); assertTrue(r.view.confirm());
			assertEquals(bits, r.view.craftingOptions().bits()); assertEquals(6, r.view.rows().size()); assertTrue(r.view.more());
			assertTrue(ItemStack.matches(before, r.carried)); assertSame(grid, r.account.state());
			assertEquals(Status.OK, r.send(PAGE, 1, 0).status());
			assertEquals(4, r.view.rows().size()); assertFalse(r.view.more()); assertEquals(bits, r.view.craftingOptions().bits());
			assertEquals(Status.PATTERN_ENCODED, r.send(PATTERN_APPLY, 1, 0).status());
			assertTrue(AEItems.CRAFTING_PATTERN.is(r.carried)); assertEquals(4, r.carried.getCount());
			var pattern = r.carried.get(AEComponents.ENCODED_CRAFTING_PATTERN); assertNotNull(pattern);
			assertEquals((bits & 1) != 0, pattern.canSubstitute()); assertEquals((bits & 2) != 0, pattern.canSubstituteFluids());
			assertEquals(before.get(DataComponents.CUSTOM_NAME), r.carried.get(DataComponents.CUSTOM_NAME));
			assertTrue(ItemStack.matches(r.carried, r.cursor.item())); assertSame(grid, r.account.state());
			assertEquals(bits, r.view.craftingOptions().bits());
			assertEquals(Status.STALE, r.send(PATTERN_APPLY, 0, 0).status()); verify(r.menu, times(1)).broadcastFullState();
		}
	}
	@Test void optionChangesInvalidateOldPreviewAndExplicitReadResetsOptions() throws Exception {
		try (var r = new Rig()) {
			r.encode(3); long old = r.view.revision(); r.encode(1); assertNotEquals(old, r.view.revision());
			r.tick += 5;
			assertEquals(Status.STALE, r.session.request(r.player, new MeTerminalRequest(1, r.id, ++r.sequence,
					PATTERN_APPLY, old, -1, 0, 0, "")).status());
			assertTrue(AEItems.BLANK_PATTERN.is(r.carried));
			assertEquals(Status.OK, r.send(PATTERN_READ, 0, 0).status()); assertEquals(CraftingOptions.DEFAULT, r.view.craftingOptions());
			r.session.close(); assertEquals(Status.OK, r.send(PATTERN_READ, 0, 0).status());
			assertEquals(CraftingOptions.DEFAULT, r.view.craftingOptions());
		}
	}
	@Test void changedOutputAndRemovedRecipeKeepBlankPatternsAndMaterials() throws Exception {
		for (boolean remove : new boolean[]{false, true}) try (var r = new Rig()) {
			r.encode(3); var before = r.carried.copy(); var grid = r.account.state();
			if (remove) when(r.recipes.byKey(r.holder.id())).thenReturn(Optional.empty());
			else r.output = new ItemStack(Items.GOLD_BLOCK);
			assertEquals(Status.STALE, r.send(PATTERN_APPLY, 0, 0).status());
			assertTrue(ItemStack.matches(before, r.carried)); assertSame(grid, r.account.state());
			verify(r.menu, never()).setCarried(any());
		}
	}
	@Test void expiredOrInvalidMenuCannotCommit() throws Exception {
		for (boolean expire : new boolean[]{false, true}) try (var r = new Rig()) {
			r.encode(3); var before = r.carried.copy();
			if (expire) r.tick += 601; else when(r.menu.stillValid(r.player)).thenReturn(false);
			var reply = r.send(PATTERN_APPLY, 0, 0);
			assertTrue(reply.status() == Status.STALE || reply.status() == Status.INVALID);
			assertTrue(ItemStack.matches(before, r.carried)); verify(r.menu, never()).setCarried(any());
		}
	}
	@Test void replyRoundTripPreservesOptionsAndRejectsMalformedOrForeignFlags() throws Exception {
		try (var r = new Rig()) {
			for (int bits = 0; bits < 4; bits++) {
				var view = r.encode(bits).withReceipt(new Receipt("retained", 2, 1)).status(Status.BUSY);
				var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), r.registries);
				try {
					MeTerminalReply.CODEC.encode(buffer, new MeTerminalReply(1, r.id, 1, view));
					int end = buffer.writerIndex(); assertTrue(end <= MeTerminalReply.MAX_BYTES);
					var decoded = MeTerminalReply.CODEC.decode(buffer).view();
					assertEquals(view.craftingOptions(), decoded.craftingOptions()); assertEquals(view.receipt(), decoded.receipt());
					buffer.readerIndex(0); buffer.setByte(end - 1, 4);
					assertThrows(IllegalArgumentException.class, () -> MeTerminalReply.CODEC.decode(buffer));
					buffer.readerIndex(0); buffer.setByte(end - 1, bits); buffer.writeByte(0);
					assertThrows(IllegalArgumentException.class, () -> MeTerminalReply.CODEC.decode(buffer));
				} finally { buffer.release(); }
			}
			assertThrows(IllegalArgumentException.class, () -> MeTerminalView.empty(Status.OK).withCraftingOptions(CraftingOptions.from(1)));
			assertThrows(IllegalArgumentException.class, () -> new MeTerminalRequest(1, r.id, 1, PATTERN_ENCODE_CRAFTING, 0, -1, 0, 4, ""));
		}
	}
}
