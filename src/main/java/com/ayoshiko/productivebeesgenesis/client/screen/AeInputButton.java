package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost;
import com.ayoshiko.productivebeesgenesis.network.OpenAeInputConfigPayload;
import mekanism.client.gui.GuiMekanism;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.lib.transmitter.TransmissionType;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.function.Supplier;

/** 侧面配置窗口内的输入配置入口；窗口实例独占控件，关闭后不再接受请求。 */
public final class AeInputButton extends MekanismButton {
	private final MekanismTileContainer<?> menu;
	private final TileEntityMekanism tile;
	private final IAe2InputHost host;
	private final Supplier<TransmissionType> selectedType;
	private final Tooltip openTooltip = Tooltip.create(
			Component.translatable("productivebeesgenesis.gui.ae_input_config.open"));
	private final Tooltip disabledTooltip = Tooltip.create(
			Component.translatable("productivebeesgenesis.gui.ae2_input.global_disabled"));
	private boolean closed;

	public AeInputButton(GuiMekanism<?> screen, int x, int y, MekanismTileContainer<?> menu,
			Supplier<TransmissionType> selectedType) {
		super(screen, x, y, 14, 14, Component.literal("I"), AeInputButton::requestOpen, null);
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.menu = menu;
		this.tile = menu.getTileEntity();
		if (!(tile instanceof IAe2InputHost inputHost)) {
			throw new IllegalArgumentException("AE input controls require an AE input host");
		}
		this.host = inputHost;
		this.selectedType = selectedType;
		refreshState();
	}

	@Override
	public void tick() {
		super.tick();
		refreshState();
	}

	public void refreshState() {
		visible = !closed && selectedType.get() == TransmissionType.ITEM;
		boolean globallyEnabled = ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeAeInputEnabled != null
				&& ModConfig.SERVER.mekCentrifugeAeInputEnabled.get();
		active = visible && globallyEnabled && hasCurrentMenu();
		setTooltip(globallyEnabled ? openTooltip : disabledTooltip);
	}

	private boolean hasCurrentMenu() {
		Minecraft client = Minecraft.getInstance();
		return gui() instanceof GuiMekanism<?> screen && client.screen == screen && client.player != null && client.level != null
				&& client.player.containerMenu == menu && screen.getMenu() == menu
				&& menu.getTileEntity() == tile && !tile.isRemoved() && tile.getLevel() == client.level
				&& client.level.getBlockEntity(tile.getBlockPos()) == tile;
	}

	private static boolean requestOpen(GuiElement element, double mouseX, double mouseY) {
		AeInputButton button = (AeInputButton) element;
		button.refreshState();
		if (!button.active || !button.visible) {
			return false;
		}
		return button.openConfigWindow();
	}

	private boolean openConfigWindow() {
		if (!(gui() instanceof GuiMekanism<?> screen)) {
			return false;
		}
		if (screen.getWindows().stream().anyMatch(GuiAeInputConfig.class::isInstance)) {
			return false;
		}
		GuiAeInputConfig window = new GuiAeInputConfig(screen, 20, 20, host, null);
		if (ModConfig.SERVER != null && ModConfig.SERVER.mekCentrifugeAeInputMinPages != null) {
			window.setMinPages(ModConfig.SERVER.mekCentrifugeAeInputMinPages.get());
		}
		screen.addWindow(window);
		PacketDistributor.sendToServer(new OpenAeInputConfigPayload(tile.getBlockPos()));
		return true;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() {
		return false;
	}

	@Override
	public void onWindowClose() {
		closed = true;
		visible = false;
		active = false;
		super.onWindowClose();
	}
}
