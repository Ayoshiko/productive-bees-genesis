package com.ayoshiko.productivebeesgenesis.apiary.client;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryGuiLayoutHelper;
import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryFactoryContainer;
import com.ayoshiko.productivebeesgenesis.apiary.TileEntityMekApiaryFactory;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
	 * MEK 蜂箱工厂版 GUI
	 * <br/>
	 * 继承 {@link GuiMekApiary}，针对工厂级蜂箱（多槽位、宽 GUI）进行扩展：
	 * <ul>
	 *   <li>从 tile 动态读取蜜蜂列数/行数与输出列数，覆盖父类的固定值</li>
	 *   <li>根据槽位规模计算并设置 GUI 宽度/高度与物品栏标签位置</li>
	 *   <li>追加排序 Tab（{@link GuiApiarySortingTab}）</li>
	 * </ul>
	 */
public class GuiMekApiaryFactory extends GuiMekApiary<TileEntityMekApiaryFactory, MekApiaryFactoryContainer> {

	private final int beeCols;
	private final int beeRows;
	private final int outputCols;
	private final int factoryImageWidth;

	/**
	 * 构造工厂版蜂箱 GUI
	 * <br/>
	 * 从 tile 读取蜜蜂/输出槽位规模，计算 GUI 尺寸并调整物品栏标签水平对齐。
	 *
	 * @param container 蜂箱工厂容器
	 * @param inv       玩家物品栏
	 * @param title     GUI 标题组件
	 */
	public GuiMekApiaryFactory(MekApiaryFactoryContainer container, Inventory inv, Component title) {
		super(container, inv, title);
		this.beeCols = tile.getBeeCols();
		this.beeRows = tile.getBeeRows();
		this.outputCols = tile.getOutputCols();
		this.factoryImageWidth = ApiaryGuiLayoutHelper.getImageWidth(beeCols, outputCols);

		imageWidth = factoryImageWidth;
		imageHeight = ApiaryGuiLayoutHelper.getImageHeight(beeRows, outputCols);
		inventoryLabelY = ApiaryGuiLayoutHelper.getInventoryLabelY(beeRows);
		// 宽 GUI（Ultimate/ME/EME）需动态对齐物品栏标签，否则 "Inventory" 文字偏左
		inventoryLabelX = ApiaryGuiLayoutHelper.getInventoryLabelX(factoryImageWidth);
	}

	@Override
	protected int getBeeCols() {
		return beeCols;
	}

	@Override
	protected int getBeeRows() {
		return beeRows;
	}

	@Override
	protected int getOutputCols() {
		return outputCols;
	}

	@Override
	protected void addGuiElements() {
		super.addGuiElements();
		addRenderableWidget(new GuiApiarySortingTab(this, tile));
	}
}
