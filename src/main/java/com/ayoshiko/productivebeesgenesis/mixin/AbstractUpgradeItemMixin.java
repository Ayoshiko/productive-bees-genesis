package com.ayoshiko.productivebeesgenesis.mixin;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeInventorySlot;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import cy.jdkdigital.productivelib.common.item.AbstractUpgradeItem;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
	 * AbstractUpgradeItem Mixin — shift+右键对机器安装 PB 升级时一次填满到上限
	 * <br/>
	 * <b>问题背景</b>：PB 原版 {@link AbstractUpgradeItem#useOn} 在 shift+右键安装升级时
	 * 强制 {@code stack.setCount(1)} 后调用 {@code insertItem}，每次仅安装 1 个，
	 * 即使玩家手持 64 个升级也需右键 64 次。
	 * <p>
	 * <b>修复方案</b>：参照 MEK 原版 {@code ItemUpgrade.useOn} 传入 {@code stack.getCount()}
	 * 给 {@code addUpgrades(upgrade, count)}，由 {@code Math.min(max-installed, maxAvailable)}
	 * 决定实际安装数，实现"一次装满到上限"。
	 * <p>
	 * <b>实现原理</b>：
	 * <ul>
	 *   <li>在 {@code useOn} 方法 HEAD 处注入，cancellable = true</li>
	 *   <li>仅当玩家潜行 + 服务端 + 方块实体为 {@link IPbUpgradeProvider} 时拦截</li>
	 *   <li>通过 {@link PbUpgradeInventorySlot#getUpgradeType} 提取升级类型</li>
	 *   <li>调用 {@link IPbUpgradeProvider#installPbUpgradeBulk} 批量安装</li>
	 *   <li>安装成功时 {@code stack.shrink(added)} 消耗物品，返回 {@link InteractionResult#SUCCESS}</li>
	 *   <li>不满足条件时不干预，让 PB 原版 useOn 逻辑执行</li>
	 * </ul>
	 * <p>
	 * <b>类加载安全</b>：本 Mixin 仅引用 {@link IPbUpgradeProvider}（本模组接口）和
	 * {@link PbUpgradeInventorySlot}/{@link PbUpgradeType}（本模组类），不依赖 ME/EME 可选 mod。
	 * 通过 {@code instanceof IPbUpgradeProvider} 多态调用避免引用具体子类
	 * （如 {@code TileEntityExtraMekCentrifugeFactory} 引用 ME 类），
	 * 故本 Mixin 始终应用，无需在 {@link MixinConfigPlugin} 中条件过滤。
	 * <p>
	 * <b>线程安全</b>：useOn 在服务端主线程被调用（玩家右键交互），无并发。
	 *
	 * @since 2.0.0
	 * @author Ayoshiko
	 */
@Mixin(value = AbstractUpgradeItem.class, remap = false)
public abstract class AbstractUpgradeItemMixin {

	/**
	 * 拦截 useOn 方法 — 在 HEAD 处注入，条件性取消原方法
	 * <br/>
	 * 满足以下全部条件时取消原 useOn 并返回 SUCCESS：
	 * <ol>
	 *   <li>玩家非 null 且 {@code player.isShiftKeyDown()}</li>
	 *   <li>世界非 null 且服务端侧（{@code !level.isClientSide}）</li>
	 *   <li>手持物品为 PB 升级（{@link PbUpgradeInventorySlot#getUpgradeType} 非 null）</li>
	 *   <li>方块实体为 {@link IPbUpgradeProvider}（蜂箱/离心机/工厂版）</li>
	 *   <li>批量安装返回值 {@code > 0}（实际安装至少 1 个）</li>
	 * </ol>
	 * 任一条件不满足时不取消，让 PB 原版 useOn 逻辑执行（保持向后兼容）。
	 *
	 * @param context 右键上下文（含玩家、世界、手持物品、点击坐标）
	 * @param cir     回调信息（可设置返回值取消原方法）
	 */
	@Inject(method = "useOn", at = @At("HEAD"), cancellable = true, remap = false)
	private void productivebeesgenesis$installPbUpgradeBulk(
			UseOnContext context, CallbackInfoReturnable<InteractionResult> cir) {
		// 1. 玩家与潜行检查 — PB 原版 useOn 也会检查，但提前返回避免无谓查方块实体
		Player player = context.getPlayer();
		if (player == null || !player.isShiftKeyDown()) return;

		// 2. 服务端检查 — 安装操作只在服务端执行
		Level level = context.getLevel();
		if (level == null || level.isClientSide) return;

		// 3. 手持物品非空检查
		ItemStack stack = context.getItemInHand();
		if (stack.isEmpty()) return;

		// 4. 提取 PB 升级类型 — 非 PB 升级物品（如 MEK 升级）返回 null，让 PB 原版处理
		PbUpgradeType type = PbUpgradeInventorySlot.getUpgradeType(stack);
		if (type == null) return;

		// 5. 方块实体为 IPbUpgradeProvider 检查 — 仅拦截本模组的蜂箱/离心机/工厂版
		BlockEntity blockEntity = level.getBlockEntity(context.getClickedPos());
		if (!(blockEntity instanceof IPbUpgradeProvider provider)) return;

		// 6. 批量安装 — 由具体 tile 实现决定实际安装数（受类型上限限制）
		int added = provider.installPbUpgradeBulk(type, stack.getCount());
		if (added <= 0) {
			// 已达上限或无法安装 — 必须取消原 useOn，防止 PB 上游 bug 无条件消耗物品
			// PB 原版 useOn 调用 insertItem(false) 后无视返回值，无条件设置 hasInsertedUpgrade=true 并 shrink(1)
			// 此处返回 PASS 不消耗物品、不挥手，规避物品丢失
			cir.setReturnValue(InteractionResult.PASS);
			return;
		}

		// 7. 消耗物品 — 非创造模式按实际安装数缩减手持堆叠
		if (!player.isCreative()) {
			stack.shrink(added);
		}

		// 8. 挥手动画 — 与 PB 原版 useOn 行为一致，反馈玩家安装成功
		player.swing(context.getHand());

		// 9. 返回 SUCCESS 并取消原 useOn — 跳过 PB 原版的 setCount(1) + insertItem 逻辑
		cir.setReturnValue(InteractionResult.SUCCESS);
	}
}
