package com.ayoshiko.productivebeesgenesis;

import java.util.Collection;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;

import cy.jdkdigital.productivebees.ProductiveBees;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

/**
 * 创造模式物品栏事件处理器
 * <br/>
 * 负责在万象创世蜜蜂被禁用时，从创造模式物品栏中隐藏其刷怪蛋。
 * <p>
 * 原理：拦截 {@link BuildCreativeModeTabContentsEvent} 事件，使用 {@link BuildCreativeModeTabContentsEvent#remove}
 * 方法从父标签页和搜索标签页中移除万象创世蜜蜂的刷怪蛋。
 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class CreativeTabEventHandler {

	private CreativeTabEventHandler() {}

	/**
	 * 拦截创造模式物品栏内容构建事件
	 * <br/>
	 * 当万象创世蜜蜂被禁用时，从创造模式物品栏中移除其刷怪蛋。
	 * 使用 {@link BuildCreativeModeTabContentsEvent#remove} 方法同时从父标签页和搜索标签页中移除。
	 */
	@SubscribeEvent
	public static void onBuildCreativeModeTabContents(BuildCreativeModeTabContentsEvent event) {
		// 检查配置是否加载以及万象创世是否被禁用
		if (!ModConfig.SERVER_SPEC.isLoaded()) {
			return; // 配置未加载，不干预（保持默认行为）
		}
		if (ModConfig.SERVER.myriadCreationsEnabled.get()) {
			return; // 万象创世已启用，无需隐藏
		}

		// 只处理 ProductiveBees 的标签页和刷怪蛋标签页
		if (!event.getTabKey().equals(ProductiveBees.TAB_KEY)
				&& !event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.SPAWN_EGGS)) {
			return;
		}

		// 获取万象创世蜜蜂的刷怪蛋物品
		ResourceLocation myriadSpawnEggId = ResourceLocation.fromNamespaceAndPath(
				PBConstants.PRODUCTIVE_BEES_MOD_ID, "spawn_egg_" + PBConstants.MYRIADCREATIONS_TYPE.getPath());
		var myriadSpawnEgg = BuiltInRegistries.ITEM.get(myriadSpawnEggId);

		// 从父标签页中移除万象创世刷怪蛋
		removeSpawnEggsFromList(event.getParentEntries(), myriadSpawnEgg, event, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
		// 从搜索标签页中移除万象创世刷怪蛋
		removeSpawnEggsFromList(event.getSearchEntries(), myriadSpawnEgg, event, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
	}

	/**
	 * 从条目列表中移除万象创世蜜蜂的刷怪蛋
	 * <br/>
	 * 遍历列表的副本（避免并发修改），检查每个ItemStack，如果是万象创世刷怪蛋则调用event.remove()
	 */
	private static void removeSpawnEggsFromList(
			Collection<ItemStack> entries,
			net.minecraft.world.item.Item myriadSpawnEgg,
			BuildCreativeModeTabContentsEvent event,
			CreativeModeTab.TabVisibility visibility) {
		for (ItemStack stack : entries) {
			if (stack.isEmpty()) continue;
			if (isMyriadCreationsSpawnEgg(stack, myriadSpawnEgg)) {
				event.remove(stack, visibility);
			}
		}
	}

	/**
	 * 检查物品栈是否为万象创世蜜蜂的刷怪蛋
	 * <br/>
	 * 两种情况：
	 * 1. 直接是 spawn_egg_myriadcreations 物品
	 * 2. 是 configurable_spawn_egg 但携带了万象创世蜜蜂类型数据
	 */
	private static boolean isMyriadCreationsSpawnEgg(ItemStack stack, net.minecraft.world.item.Item myriadSpawnEgg) {
		if (stack.getItem() instanceof SpawnEggItem) {
			// 直接匹配万象创世刷怪蛋
			if (myriadSpawnEgg != null && stack.getItem() == myriadSpawnEgg) {
				return true;
			}

			// 检查是否为 Configurable Spawn Egg 携带万象创世类型
			if (stack.getItem() == ModItems.CONFIGURABLE_SPAWN_EGG.get()) {
				var entityData = stack.get(net.minecraft.core.component.DataComponents.ENTITY_DATA);
				if (entityData != null) {
					var nbt = entityData.getUnsafe();
					if (nbt != null) {
						String beeType = nbt.getString("type");
						if (PBConstants.MYRIADCREATIONS_TYPE_STRING.equals(beeType)) {
							return true;
						}
					}
				}
			}
		}
		return false;
	}
}
