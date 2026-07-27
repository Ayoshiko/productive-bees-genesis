package com.ayoshiko.productivebeesgenesis.client.screen;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.ayoshiko.productivebeesgenesis.mek.ae2.CombFuzzyMatcher;
import com.ayoshiko.productivebeesgenesis.util.BeeInfoHelper;

import mekanism.client.gui.IGuiWrapper;
import mekanism.client.gui.element.GuiElement;
import mekanism.client.gui.element.slot.SlotType;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget.IGhostIngredientConsumer;
import mekanism.client.recipe_viewer.interfaces.IRecipeViewerGhostTarget.IGhostItemConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 轻量 ghost slot 组件 — MEK GuiElement 子类，不消耗玩家物品
 * <br/>
 * 仅持有 {@link ResourceLocation} beeType 标识（不持有真实 ItemStack 引用），
 * 渲染时通过 {@link BeeInfoHelper#resolveBeeIcon} 获取代表 ItemStack 作只读展示，
 * 玩家物品栏不受影响。
 * <p>
 * <b>V13 变更</b>：
 * <ul>
 *   <li>修复 mouseClicked 覆写问题（原 onClick 方法不会被 GuiElement 调用）</li>
 *   <li>左键和右键都可标记：携带光标物品时，左键/右键都触发添加到当前 slot 位置</li>
 *   <li>左键/右键空光标 + slot 已填充：触发移除回调（V14：左键也可取消）</li>
 *   <li>新增 slotIndex 字段，支持位置固定模式（放到哪个格子就在哪个格子）</li>
 *   <li>新增 isBlock 字段，精确模式下区分蜜脾和蜜脾块</li>
 * </ul>
 * <p>
 * <b>JEI 集成</b>：实现 {@link IRecipeViewerGhostTarget}，Mekanism 已注册的
 * {@code JeiGhostIngredientHandler} 会自动发现本组件并路由 JEI 拖拽物品。
 * 仅接受蜜脾类物品（通过 {@link CombFuzzyMatcher#getBeeType} 校验）。
 * <p>
 * <b>V19 变更</b>：修复方块渲染棱角不完整问题。
 * <ul>
 *   <li>物品渲染从 {@code renderWidget} 迁移到 {@code drawBackground}，对齐 MEK {@code GuiSequencedSlotDisplay} 模式</li>
 *   <li>slot 背景保留在 {@code renderWidget}，对齐 MEK {@code GuiSlot} 默认模式（renderAboveSlots=false）</li>
 *   <li>MEK 渲染顺序：先所有子元素 renderWidget，后所有子元素 drawBackground</li>
 *   <li>MEK 原版过滤器：GuiSlot.renderWidget（背景）→ GuiSequencedSlotDisplay.drawBackground（物品）</li>
 * </ul>
 */
public final class GhostItemWidget extends GuiElement implements IRecipeViewerGhostTarget {

	/** ghost slot 尺寸（宽=高，与 SlotType.NORMAL 一致） */
	public static final int SIZE = 18;

	private ResourceLocation beeType;
	private boolean isBlock;
	private final int slotIndex;

	private final BiConsumer<Integer, ItemStack> placeCallback;
	private final Consumer<Integer> removeCallback;

	/**
	 * 构造 ghost slot 组件
	 *
	 * @param gui           所属 GUI 包装器
	 * @param x             相对 GUI 左上角 X 坐标
	 * @param y             相对 GUI 左上角 Y 坐标
	 * @param slotIndex     slot 在网格中的全局索引（用于位置固定模式）
	 * @param beeType       初始蜜蜂类型（null 表示空 slot）
	 * @param isBlock       是否为蜜脾块
	 * @param placeCallback 放置回调（左键/右键携带光标物品时触发，参数为 slotIndex + ItemStack）
	 * @param removeCallback 移除回调（右键空光标 + slot 已填充时触发，参数为 slotIndex）
	 */
	public GhostItemWidget(IGuiWrapper gui, int x, int y, int slotIndex,
			ResourceLocation beeType, boolean isBlock,
			BiConsumer<Integer, ItemStack> placeCallback,
			Consumer<Integer> removeCallback) {
		super(gui, x, y, SIZE, SIZE);
		this.slotIndex = slotIndex;
		this.beeType = beeType;
		this.isBlock = isBlock;
		this.placeCallback = placeCallback;
		this.removeCallback = removeCallback;
	}

	/** 是否为空 slot */
	public boolean isEmpty() {
		return beeType == null;
	}

	public ResourceLocation getBeeType() {
		return beeType;
	}

	public boolean isBlock() {
		return isBlock;
	}

	public void setEntry(ResourceLocation beeType, boolean isBlock) {
		this.beeType = beeType;
		this.isBlock = isBlock;
	}

	public void clear() {
		this.beeType = null;
		this.isBlock = false;
	}

	public int getSlotIndex() {
		return slotIndex;
	}

	/**
	 * JEI ghost ingredient 目标处理器 — 返回仅接受蜜脾类物品的消费者
	 */
	@Override
	public IGhostIngredientConsumer getGhostHandler() {
		return new CombGhostConsumer();
	}

	private final class CombGhostConsumer implements IGhostItemConsumer {

		@Nullable
		@Override
		public ItemStack supportedTarget(Object ingredient) {
			if (ingredient instanceof ItemStack stack && !stack.isEmpty()) {
				return CombFuzzyMatcher.getBeeType(stack) != null ? stack : null;
			}
			return null;
		}

		@Override
		public void accept(Object ingredient) {
			if (ingredient instanceof ItemStack stack) {
				acceptGhostIngredient(stack);
			}
		}
	}

	/** 命中检测 — 供外部 JEI 拖拽路由使用 */
	public boolean contains(double mouseX, double mouseY) {
		return isMouseOver(mouseX, mouseY);
	}

	/**
	 * slot 背景在 renderWidget 阶段渲染，对齐 MEK {@link mekanism.client.gui.element.slot.GuiSlot} 默认模式
	 * <br/>
	 * GuiSlot 默认 {@code renderAboveSlots=false}，在 renderWidget 中渲染 slot 背景纹理。
	 * 物品渲染在 {@link #drawBackground}，对齐 MEK {@code GuiSequencedSlotDisplay} 的渲染模式。
	 * <p>
	 * MEK 渲染顺序（{@code onRenderForeground}）：先所有子元素 renderWidget，后所有子元素 drawBackground。
	 */
	@Override
	public void renderWidget(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		guiGraphics.blit(SlotType.NORMAL.getTexture(), relativeX, relativeY, 0, 0, SIZE, SIZE, SIZE, SIZE);
	}

	/**
	 * 物品在 drawBackground 阶段渲染，对齐 MEK GuiSequencedSlotDisplay
	 */
	@Override
	public void drawBackground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTicks) {
		super.drawBackground(guiGraphics, mouseX, mouseY, partialTicks);
		if (beeType == null) {
			return;
		}
		ItemStack icon = resolveIcon();
		if (!icon.isEmpty()) {
			gui().renderItem(guiGraphics, icon, relativeX + 1, relativeY + 1);
		}
	}

	private ItemStack resolveIcon() {
		Level level = Minecraft.getInstance().level;
		if (level == null) {
			return ItemStack.EMPTY;
		}
		return BeeInfoHelper.resolveBeeIcon(level, beeType, this.isBlock);
	}

	/**
	 * 鼠标点击处理 — 左键/右键携带光标物品都可标记，左键/右键空光标都可移除
	 * <br/>
	 * <b>V13 修复</b>：原 {@code onClick(double, double, int)} 方法不会被 GuiElement 调用，
	 * 改为重写 {@code mouseClicked} 修复右键和左键标记不触发的问题。
	 * <ul>
	 *   <li>左键 + 光标携带物品：接受为 ghost 条目（放置到当前 slotIndex 位置）</li>
	 *   <li>右键 + 光标携带物品：同上（左键右键都可标记）</li>
	 *   <li>左键/右键 + 空光标 + slot 已填充：触发移除回调</li>
	 * </ul>
	 */
	@Override
	public boolean mouseClicked(double mouseX, double mouseY, int button) {
		if (!isMouseOver(mouseX, mouseY)) {
			return false;
		}
		ItemStack carried = gui().getCarriedItem();
		if (button == 0 || button == 1) {
			// 左键或右键 + 光标携带物品：标记到当前 slot 位置
			if (!carried.isEmpty()) {
				acceptGhostIngredient(carried);
				return true;
			}
			// 左键/右键 + 空光标 + slot 已填充：移除
			if (beeType != null && removeCallback != null) {
				removeCallback.accept(slotIndex);
				return true;
			}
		}
		return false;
	}

	/**
	 * 接收幽灵物品 — 统一入口
	 * <br/>
	 * 通过 {@link CombFuzzyMatcher#getBeeType(ItemStack)} 校验是否为蜜脾类物品，
	 * 非蜜脾物品拒绝接收。校验通过后触发 placeCallback 由上层处理网络同步，
	 * 本地 beeType/isBlock 状态由服务端推送 {@link com.ayoshiko.productivebeesgenesis.network.SyncAeInputFilterEntriesPayload} 后刷新。
	 */
	public void acceptGhostIngredient(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		if (CombFuzzyMatcher.getBeeType(stack) == null) {
			return;
		}
		if (placeCallback != null) {
			placeCallback.accept(slotIndex, stack);
		}
	}

	/**
	 * 重写 renderForeground — 不绘制默认的按钮文字
	 * <br/>
	 * GuiElement 默认在 renderForeground 中调用 drawButtonText 渲染 getMessage() 返回的文字，
	 * 对于 ghost slot 来说会渲染 beeType 的完整 ID 字符串（如 productivebees:myriad_creations），
	 * 在 18×18 的 slot 内完全不可读且遮挡图标。故重写为空实现，仅依赖图标和 tooltip 展示信息。
	 */
	@Override
	public void renderForeground(@NotNull GuiGraphics guiGraphics, int mouseX, int mouseY) {
		// 不调用 super.renderForeground 以避免绘制 beeType 文字
	}

	@Override
	@NotNull
	public Component getMessage() {
		return beeType == null ? Component.empty() : Component.literal(beeType.toString());
	}
}
