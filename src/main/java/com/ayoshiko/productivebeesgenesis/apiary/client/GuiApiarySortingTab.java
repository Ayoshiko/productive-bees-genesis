package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.network.ApiaryToggleSortingPayload;
import mekanism.client.SpecialColors;
import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement.ButtonBackground;
import mekanism.client.gui.element.GuiInsetElement;
import mekanism.client.render.MekanismRenderer;
import mekanism.common.MekanismLang;
import mekanism.common.util.MekanismUtils.ResourceType;
import mekanism.common.util.MekanismUtils;
import mekanism.common.util.text.BooleanStateDisplay.OnOff;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.NotNull;

/**
	 * 通用机械蜂箱工厂排序切换 Tab
	 * <br>
	 * 1:1复刻 MEK 原版 GuiSortingTab：
	 * <ul>
	 *   <li>尺寸：宽35×高18（左侧伸出26px）</li>
	 *   <li>位置：x=-26, y=62</li>
	 *   <li>颜色：TAB_FACTORY_SORT（灰色）</li>
	 *   <li>On/Off 状态文本居中显示</li>
	 * </ul>
	 * <p>
	 * 不直接使用 MEK 原版 PacketGuiInteract.AUTO_SORT_BUTTON（该包检查 tile instanceof TileEntityFactory，
	 * 蜂箱工厂不继承 TileEntityFactory），改用自定义 {@link ApiaryToggleSortingPayload}。
	 */
public class GuiApiarySortingTab extends GuiInsetElement<TileEntityMekApiaryFactory> {

	private static final int TAB_X = -26;
	private static final int TAB_Y = 62;
	private static final int TAB_WIDTH = 35;
	private static final int TAB_HEIGHT = 18;

	public GuiApiarySortingTab(IGuiWrapper gui, TileEntityMekApiaryFactory tile) {
		super(MekanismUtils.getResource(ResourceType.GUI, "sorting.png"), gui, tile,
				TAB_X, TAB_Y, TAB_WIDTH, TAB_HEIGHT, true);
		setButtonBackground(ButtonBackground.DEFAULT);
		setTooltip(MekanismLang.AUTO_SORT);
	}

	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		drawScrollingString(guiGraphics, OnOff.of(dataSource.isSorting()).getTextComponent(),
				0, 24, TextAlignment.CENTER, titleTextColor(), 3, false);
	}

	@Override
	protected void colorTab(GuiGraphics guiGraphics) {
		MekanismRenderer.color(guiGraphics, SpecialColors.TAB_FACTORY_SORT);
	}

	@Override
	public void onClick(double mouseX, double mouseY, int button) {
		BlockPos pos = dataSource.getBlockPos();
		PacketDistributor.sendToServer(new ApiaryToggleSortingPayload(pos));
	}
}
