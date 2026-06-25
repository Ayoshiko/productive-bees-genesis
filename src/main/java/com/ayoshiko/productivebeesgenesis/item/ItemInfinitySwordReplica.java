package com.ayoshiko.productivebeesgenesis.item;

import java.util.List;

import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;

/**
 * 寰宇支配之剑验证物品
 * <br/>
 * 1:1 复刻原版剑的渲染流程，用于隔离验证 cosmic 渲染管线。
 * 不引入 Avaritia 的 Tier 与特殊攻击逻辑，仅作为渲染对照组。
 */
public class ItemInfinitySwordReplica extends SwordItem {

    public static final String MODE_TAG = "mode";
    public static final String KILL_MODE = "infinity_sword_kill";

    public ItemInfinitySwordReplica(Properties properties) {
        super(Tiers.NETHERITE, properties.attributes(SwordItem.createAttributes(Tiers.NETHERITE, 2, -2.4F)));
    }

    @Override
    public boolean isFoil(ItemStack stack) {
        return false;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack heldItem = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            boolean activated = switchKillMode(heldItem);
            if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
                serverPlayer.sendSystemMessage(Component.translatable(
                        activated
                                ? "tooltip.productivebeesgenesis.sword_kill_mode.active"
                                : "tooltip.productivebeesgenesis.sword_kill_mode.inactive"), true);
            }
            player.swing(hand);
            return InteractionResultHolder.sidedSuccess(heldItem, level.isClientSide);
        }
        return super.use(level, player, hand);
    }

    /**
     * 切换杀戮模式并返回切换后的状态
     */
    private boolean switchKillMode(ItemStack stack) {
        // 显式取出、修改再写回，避免依赖 ItemStack.update 的返回行为可能为 null
        CustomData customData = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        CompoundTag tag = customData.copyTag();
        CompoundTag modeTag = tag.contains(MODE_TAG, Tag.TAG_COMPOUND)
                ? tag.getCompound(MODE_TAG)
                : new CompoundTag();
        boolean newState = !modeTag.getBoolean(KILL_MODE);
        modeTag.putBoolean(KILL_MODE, newState);
        tag.put(MODE_TAG, modeTag);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return newState;
    }

    /**
     * 判断当前是否处于杀戮模式
     */
    public static boolean isKillModeActive(ItemStack stack) {
        CustomData customData = stack.get(DataComponents.CUSTOM_DATA);
        if (customData == null) {
            return false;
        }
        CompoundTag tag = customData.copyTag();
        if (!tag.contains(MODE_TAG, Tag.TAG_COMPOUND)) {
            return false;
        }
        return tag.getCompound(MODE_TAG).getBoolean(KILL_MODE);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltipComponents, TooltipFlag tooltipFlag) {
        super.appendHoverText(stack, context, tooltipComponents, tooltipFlag);
        if (isKillModeActive(stack)) {
            tooltipComponents.add(Component.translatable("tooltip.productivebeesgenesis.sword_kill_mode.active").withStyle(net.minecraft.ChatFormatting.RED));
        }
    }
}
