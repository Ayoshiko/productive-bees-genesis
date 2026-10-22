package com.ayoshiko.productivebeesgenesis.item;

import com.ayoshiko.productivebeesgenesis.authoring.BeeDesignerServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** 无资产槽位的管理员内容创作入口。 */
public final class BeeDesignerItem extends Item {
	public BeeDesignerItem(Properties properties) {
		super(properties.stacksTo(1));
	}

	@Override
	public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
		if (!player.isCreative()) {
			if (!level.isClientSide()) {
				player.displayClientMessage(Component.translatable("designer.error.creative_only"), true);
			}
			return InteractionResultHolder.fail(player.getItemInHand(hand));
		}
		if (player instanceof ServerPlayer serverPlayer) {
			BeeDesignerServer.open(serverPlayer);
		}
		return InteractionResultHolder.sidedSuccess(player.getItemInHand(hand), level.isClientSide());
	}

	@Override
	public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
		tooltip.add(Component.translatable("item.productivebeesgenesis.bee_designer.description").withStyle(ChatFormatting.GRAY));
		tooltip.add(Component.translatable("item.productivebeesgenesis.bee_designer.creative").withStyle(ChatFormatting.GOLD));
		tooltip.add(Component.translatable("item.productivebeesgenesis.bee_designer.server").withStyle(ChatFormatting.GRAY));
	}
}
