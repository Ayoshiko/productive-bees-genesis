package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import appeng.api.config.Settings;
import appeng.api.config.SortDir;
import appeng.api.config.SortOrder;
import appeng.api.config.ViewItems;
import appeng.api.storage.MEStorage;
import appeng.api.inventories.InternalInventory;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.api.util.IConfigManager;
import appeng.api.util.KeyTypeSelection;
import appeng.api.storage.ILinkStatus;
import appeng.core.definitions.AEItems;
import appeng.helpers.IPatternTerminalLogicHost;
import appeng.menu.me.items.PatternEncodingTermMenu;
import appeng.parts.encoding.PatternEncodingLogic;
import appeng.parts.encoding.PatternEncodingTerminalPart;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/** 真实 AE2 菜单、槽同步及编码逻辑；只替代世界、玩家和网格存储。 */
final class MachinePatternMenuFixture {

	final PatternEncodingTermMenu menu;
	final PatternEncodingLogic logic;

	MachinePatternMenuFixture(ServerLevel level) {
		var player = mock(Player.class);
		when(player.level()).thenReturn(level);
		when(player.getCommandSenderWorld()).thenReturn(level);
		var inventory = new Inventory(player);
		when(player.getInventory()).thenReturn(inventory);
		var logicHost = mock(IPatternTerminalLogicHost.class);
		when(logicHost.getLevel()).thenReturn(level);
		logic = new PatternEncodingLogic(logicHost);
		when(logicHost.getLogic()).thenReturn(logic);
		var terminal = mock(PatternEncodingTerminalPart.class);
		when(terminal.getLogic()).thenReturn(logic);
		var storage = mock(MEStorage.class, CALLS_REAL_METHODS);
		when(terminal.getInventory()).thenReturn(storage);
		when(terminal.getLinkStatus()).thenReturn(ILinkStatus.ofDisconnected(null));
		when(terminal.getKeyTypeSelection()).thenReturn(new KeyTypeSelection(() -> {}, type -> true));
		when(terminal.getViewCellStorage()).thenReturn(mock(InternalInventory.class));
		when(terminal.getUpgrades()).thenReturn(mock(IUpgradeInventory.class));
		when(terminal.getConfigManager()).thenReturn(IConfigManager.builder(() -> {})
				.registerSetting(Settings.SORT_BY, SortOrder.NAME)
				.registerSetting(Settings.VIEW_MODE, ViewItems.ALL)
				.registerSetting(Settings.SORT_DIRECTION, SortDir.ASCENDING).build());
		menu = new PatternEncodingTermMenu(PatternEncodingTermMenu.TYPE, 1, inventory, terminal, false);
	}

	ItemStack encode(List<ItemStack> grid) {
		synchronizeGrid(grid);
		logic.getBlankPatternInv().setItemDirect(0, AEItems.BLANK_PATTERN.stack());
		menu.encode();
		assertTrue(logic.getBlankPatternInv().getStackInSlot(0).isEmpty());
		ItemStack encoded = logic.getEncodedPatternInv().getStackInSlot(0);
		assertFalse(encoded.isEmpty());
		return encoded;
	}

	void assertRejected(List<ItemStack> grid) {
		synchronizeGrid(grid);
		logic.getBlankPatternInv().setItemDirect(0, AEItems.BLANK_PATTERN.stack());
		menu.encode();
		assertEquals(1, logic.getBlankPatternInv().getStackInSlot(0).getCount());
		assertTrue(logic.getEncodedPatternInv().getStackInSlot(0).isEmpty());
		for (int index = 0; index < grid.size(); index++) {
			assertTrue(ItemStack.matches(grid.get(index), menu.getCraftingGridSlots()[index].getItem()));
		}
	}

	private void synchronizeGrid(List<ItemStack> grid) {
		for (int index = 0; index < grid.size(); index++) {
			menu.setItem(menu.getCraftingGridSlots()[index].index, 1, grid.get(index).copy());
		}
		List<ItemStack> contents = new ArrayList<>(Collections.nCopies(menu.slots.size(), ItemStack.EMPTY));
		for (int index = 0; index < menu.slots.size(); index++) {
			contents.set(index, menu.slots.get(index).getItem().copy());
		}
		menu.initializeContents(2, contents, ItemStack.EMPTY);
	}
}
