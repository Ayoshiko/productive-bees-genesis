package com.ayoshiko.productivebeesgenesis.client.screen;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2OutputHostBase;
import com.ayoshiko.productivebeesgenesis.network.CycleAeOutputPayload;
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

/** 原生侧面配置窗口拥有的 AE 输出控件；点击与关闭均走 Mekanism 子元素生命周期。 */
public final class AeOutputButton extends MekanismButton {
	private final MekanismTileContainer<?> menu;
	private final TileEntityMekanism tile;
	private final IAe2OutputHostBase host;
	private final Supplier<TransmissionType> selectedType;
	private final Tooltip toggleTooltip = Tooltip.create(
			Component.translatable("productivebeesgenesis.gui.ae2_output.button"));
	private final Tooltip disabledTooltip = Tooltip.create(
			Component.translatable("productivebeesgenesis.gui.ae2_output.global_disabled"));
	private CycleAeOutputPayload.OutputType outputType;
	private boolean outputEnabled;
	private boolean closed;

	public AeOutputButton(GuiMekanism<?> screen, int x, int y, MekanismTileContainer<?> menu,
			Supplier<TransmissionType> selectedType) {
		super(screen, x, y, 14, 14, Component.literal("A"), AeOutputButton::requestToggle, null);
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
		this.menu = menu;
		this.tile = menu.getTileEntity();
		if (!(tile instanceof IAe2OutputHostBase outputHost)) {
			throw new IllegalArgumentException("AE output controls require an AE output host");
		}
		this.host = outputHost;
		this.selectedType = selectedType;
		refreshState();
	}

	@Override
	public void tick() {
		super.tick();
		refreshState();
	}

	/** 页签切换时同步可见性；点击前也复核一次，避免使用上一刻的类型或菜单。 */
	public void refreshState() {
		if (closed) {
			visible = false;
			active = false;
			return;
		}
		TransmissionType type = selectedType.get();
		outputType = switch (type) {
			case ITEM -> CycleAeOutputPayload.OutputType.ITEM;
			case FLUID -> CycleAeOutputPayload.OutputType.FLUID;
			default -> null;
		};
		visible = outputType != null;
		boolean globallyEnabled = globalOutputEnabled(type);
		active = visible && globallyEnabled && hasCurrentMenu();
		outputEnabled = switch (type) {
			case ITEM -> host.productivebeesgenesis$isAeItemOutputEnabled();
			case FLUID -> host.productivebeesgenesis$isAeFluidOutputEnabled();
			default -> false;
		};
		setTooltip(globallyEnabled ? toggleTooltip : disabledTooltip);
	}

	private boolean globalOutputEnabled(TransmissionType type) {
		if (tile instanceof TileEntityMekApiary) {
			return switch (type) {
				case ITEM -> ModConfig.SERVER.apiaryAeOutputEnabled.get();
				case FLUID -> ModConfig.SERVER.apiaryAeFluidOutputEnabled.get();
				default -> false;
			};
		}
		return switch (type) {
			case ITEM -> ModConfig.SERVER.mekCentrifugeAeOutputEnabled.get();
			case FLUID -> ModConfig.SERVER.mekCentrifugeAeFluidOutputEnabled.get();
			default -> false;
		};
	}

	private boolean hasCurrentMenu() {
		Minecraft client = Minecraft.getInstance();
		return client.screen == gui() && client.player != null
				&& client.player.containerMenu == menu && !tile.isRemoved();
	}

	private static boolean requestToggle(GuiElement element, double mouseX, double mouseY) {
		AeOutputButton button = (AeOutputButton) element;
		button.refreshState();
		if (!button.active || !button.visible || button.outputType == null) {
			return false;
		}
		PacketDistributor.sendToServer(new CycleAeOutputPayload(button.tile.getBlockPos(), button.outputType));
		return true;
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		if (active && outputEnabled) {
			return 0x009E45;
		}
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
