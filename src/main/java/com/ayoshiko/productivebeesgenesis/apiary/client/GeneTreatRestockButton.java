package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiary;
import com.ayoshiko.productivebeesgenesis.mek.ae2.Ae2IntegrationLoader;
import com.ayoshiko.productivebeesgenesis.network.ToggleApiaryGeneTreatRestockPayload;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.button.MekanismButton;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** 基因小食槽下方的补货开关；点击仅请求服务端修改。 */
final class GeneTreatRestockButton extends MekanismButton {

	private final TileEntityMekApiary tile;
	private int tooltipState = -1;

	GeneTreatRestockButton(IGuiWrapper gui, TileEntityMekApiary tile) {
		super(gui, ApiaryGuiLayoutHelper.GENE_TREAT_X - 1,
				ApiaryGuiLayoutHelper.GENE_TREAT_Y + 19, 18, 14,
				Component.translatable("gui.productivebeesgenesis.gene_treat_restock.button"),
				(element, mouseX, mouseY) -> {
					PacketDistributor.sendToServer(new ToggleApiaryGeneTreatRestockPayload(tile.getBlockPos()));
					return true;
				});
		this.tile = tile;
		setButtonBackground(GuiElement.ButtonBackground.DEFAULT);
	}

	@Override
	public void updateTooltip(int mouseX, int mouseY) {
		int state = !Ae2IntegrationLoader.isAe2Loaded() ? 3
				: tile.getGeneTreatRestock().isSuspended() ? 2 : tile.isGeneTreatRestockEnabled() ? 1 : 0;
		if (state == tooltipState) return;
		tooltipState = state;
		String suffix = switch (state) {
			case 1 -> "enabled";
			case 2 -> "suspended";
			case 3 -> "unavailable";
			default -> "disabled";
		};
		setTooltip(Tooltip.create(Component.translatable("gui.productivebeesgenesis.gene_treat_restock." + suffix)));
	}

	@Override
	protected int getButtonTextColor(int mouseX, int mouseY) {
		return tile.getGeneTreatRestock().isSuspended() ? 0xC00000
				: tile.isGeneTreatRestockEnabled() ? 0x009E45 : 0x232323;
	}

	@Override
	protected boolean displayButtonTextShadow() { return false; }
}
