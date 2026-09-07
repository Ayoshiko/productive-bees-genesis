package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import com.ayoshiko.productivebeesgenesis.apiary.IPagedOutputContainer;
import mekanism.client.gui.IGuiWrapper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.AbstractContainerMenu;

import java.util.function.Consumer;

/**
 * 输出区翻页控件（不可变值对象 + 静态工厂）
 * <br/>
 * 从 {@code GuiMekApiary} 拆分而来，职责（SRP）：输出区翻页按钮的创建、定位与页码文本绘制。
 * <p>
 * 拆分动因：原实现里"按钮定位"与"页码文本定位"两处各自重算同一串布局算式
 * （outputX / outputWidth / outputBottom），改一处忘另一处就会出现按钮与文字错位。
 * 现在两者共用同一份预计算坐标。
 */
final class ApiaryOutputPageControls {

	/** 翻页按钮边长（12×12，比喂食窗口的 18×12 更紧凑，适配输出区下方窄条） */
	private static final int BUTTON_SIZE = 12;

	private final int buttonY;
	private final int previousX;
	private final int nextX;
	private final int outputX;
	private final int outputWidth;

	private ApiaryOutputPageControls(int buttonY, int previousX, int nextX, int outputX, int outputWidth) {
		this.buttonY = buttonY;
		this.previousX = previousX;
		this.nextX = nextX;
		this.outputX = outputX;
		this.outputWidth = outputWidth;
	}

	/**
	 * 按当前蜂箱尺寸计算翻页控件布局
	 *
	 * @param imageWidth GUI 宽度
	 * @param beeCols    蜜蜂槽列数
	 * @param beeRows    蜜蜂槽行数
	 * @param outputCols 输出槽列数
	 */
	static ApiaryOutputPageControls of(int imageWidth, int beeCols, int beeRows, int outputCols) {
		int outputWidth = ApiaryGuiLayoutHelper.getOutputW(outputCols);
		int outputX = ApiaryGuiLayoutHelper.getOutputX(
				ApiaryGuiLayoutHelper.getBeeX(imageWidth, beeCols),
				ApiaryGuiLayoutHelper.getBeeW(beeCols), outputWidth);
		int outputBottom = ApiaryGuiLayoutHelper.getOutputY(
				ApiaryGuiLayoutHelper.getBeeBottom(beeRows), beeRows)
				+ ApiaryGuiLayoutHelper.getOutputH();
		return new ApiaryOutputPageControls(
				ApiaryGuiLayoutHelper.getOutputPageButtonY(outputBottom),
				ApiaryGuiLayoutHelper.getOutputPagePreviousButtonX(outputX, outputWidth),
				ApiaryGuiLayoutHelper.getOutputPageNextButtonX(outputX, outputWidth),
				outputX, outputWidth);
	}

	/**
	 * 创建并注册 ◀/▶ 两个翻页按钮
	 * <br/>
	 * 单页容器不注册按钮（调用方已判定），按钮点击同时推进客户端页码并发
	 * {@code handleInventoryButtonClick} 通知服务端，保持两端页码一致。
	 *
	 * @param gui      所属 GUI 包装器
	 * @param paged    分页容器
	 * @param menu     容器实例（取 containerId）
	 * @param register 元素注册回调（通常为 {@code this::addRenderableWidget}）
	 */
	void addButtons(IGuiWrapper gui, IPagedOutputContainer paged, AbstractContainerMenu menu,
			Consumer<FeederPageButton> register) {
		FeederPageButton previous = new FeederPageButton(gui, previousX, buttonY, BUTTON_SIZE, BUTTON_SIZE,
				"\u25C0", () -> changePage(paged, menu, -1, IPagedOutputContainer.PREVIOUS_OUTPUT_PAGE_BUTTON));
		previous.setTooltip(Tooltip.create(Component.translatable(
				"gui.productivebeesgenesis.output_page.prev.tooltip")));
		FeederPageButton next = new FeederPageButton(gui, nextX, buttonY, BUTTON_SIZE, BUTTON_SIZE,
				"\u25B6", () -> changePage(paged, menu, 1, IPagedOutputContainer.NEXT_OUTPUT_PAGE_BUTTON));
		next.setTooltip(Tooltip.create(Component.translatable(
				"gui.productivebeesgenesis.output_page.next.tooltip")));
		register.accept(previous);
		register.accept(next);
	}

	/** 绘制 "当前页/总页数" 文本，水平居中于输出区 */
	void renderPageText(GuiGraphics guiGraphics, Font font, IPagedOutputContainer paged) {
		String pageText = (paged.getOutputPage() + 1) + "/" + paged.getOutputPageCount();
		int textX = outputX + (outputWidth - font.width(pageText)) / 2;
		guiGraphics.drawString(font, pageText, textX, buttonY + 2, 0x404040, false);
	}

	private static void changePage(IPagedOutputContainer paged, AbstractContainerMenu menu,
			int delta, int buttonId) {
		paged.changeOutputPage(delta);
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.gameMode != null) {
			minecraft.gameMode.handleInventoryButtonClick(menu.containerId, buttonId);
		}
	}
}
