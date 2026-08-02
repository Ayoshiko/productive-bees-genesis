package com.ayoshiko.productivebeesgenesis.apiary;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeBlock;
import com.ayoshiko.productivebeesgenesis.util.WrenchCapabilityHelper;

import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.WorldUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/**
 * 通用扳手 shift+右键拆卸事件处理器 — 修复 omnitools/AE2 等通用扳手无法拆卸本模组机器
 * <br/>
 * <b>根因</b>：omnitools 的 {@code MekanismTransmitterWrenchHandler} 对任何暴露
 * {@code Capabilities.CONFIGURABLE}（实现 {@code IConfigurable}）的 MEK 方块
 * {@code canHandle=true}（包括我们的蜂箱/离心机），其 {@code handle}（服务端）直接调用
 * {@code IConfigurable.onSneakRightClick(player)} 打开配置 UI 并返回 consumesAction。
 * 该 handler 在 {@code OmniToolItem.useOn}（即 {@code Item.useOn}）中执行，<b>先于
 * {@code Block.useItemOn}</b>，因此方块内的 shift+扳手拆卸分支永远不会被执行。
 * <p>
 * <b>修复</b>：监听 {@link PlayerInteractEvent.RightClickBlock}（NeoForge 在
 * {@code Item.useOn} 之前派发），服务端以 HIGHEST 优先级处理：
 * <ol>
 *   <li>shift 键按下 + 手持通用扳手 + 目标是本模组机器（蜂箱/离心机）</li>
 *   <li>直接调用 {@link WorldUtils#dismantleBlock} 执行拆卸</li>
 *   <li>取消事件并返回 SUCCESS，阻止后续 {@code Item.useOn}（omnitools 拦截点）执行</li>
 * </ol>
 * <p>
 * <b>客户端策略</b>：客户端<b>不</b>取消事件（取消会导致不发送
 * {@code ServerboundUseItemOnPacket}，服务端无感知），让交互正常进行：
 * 客户端 {@code Block.useItemOn} 已对扳手返回 SUCCESS 阻止 GUI 打开，同时发送交互包，
 * 服务端收到包后本处理器执行拆卸，两端结果一致（SUCCESS）。
 * <p>
 * 设计原则：SRP（仅负责扳手拆卸拦截）、无状态（工具类 + 静态事件方法）。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class ApiaryWrenchDismantleHandler {

	private ApiaryWrenchDismantleHandler() {
		// 工具类禁止实例化
	}

	/**
	 * 服务端 shift+扳手右键本模组机器时执行拆卸并取消事件
	 * <br/>
	 * 仅在服务端处理（客户端不取消，保证交互包发送）。非 shift / 非扳手 / 非本模组机器直接放行。
	 *
	 * @param event 右键方块事件
	 */
	@SubscribeEvent(priority = EventPriority.HIGHEST)
	public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
		Level level = event.getLevel();
		// 客户端不拦截：取消事件会导致不发送交互包，服务端无法执行拆卸
		if (level.isClientSide) {
			return;
		}
		Player player = event.getEntity();
		if (player == null || !player.isShiftKeyDown()) {
			return;
		}
		ItemStack stack = event.getItemStack();
		if (stack.isEmpty() || !WrenchCapabilityHelper.canUseAsWrench(stack)) {
			return;
		}
		BlockPos pos = event.getPos();
		if (!(level.getBlockEntity(pos) instanceof TileEntityMekanism tile)) {
			return;
		}
		BlockState state = level.getBlockState(pos);
		Block block = state.getBlock();
		// 仅处理本模组机器（蜂箱 / 离心机），其他方块不受影响
		if (!(block instanceof MekApiaryBlock<?, ?>) && !(block instanceof MekCentrifugeBlock<?, ?>)) {
			return;
		}
		// 辐射环境下禁止拆卸（与 MEK 原版行为一致）
		if (tile.getRadiationScale() > 0) {
			event.setCanceled(true);
			event.setCancellationResult(InteractionResult.FAIL);
			return;
		}
		// 执行拆卸并取消事件，阻止 omnitools 的 Item.useOn 拦截（打开配置 UI）
		WorldUtils.dismantleBlock(state, level, pos, tile, player, stack);
		event.setCanceled(true);
		event.setCancellationResult(InteractionResult.SUCCESS);
	}
}
