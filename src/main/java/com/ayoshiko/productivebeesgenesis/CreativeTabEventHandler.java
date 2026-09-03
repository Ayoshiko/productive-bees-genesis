package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.config.ModConfig;
import com.ayoshiko.productivebeesgenesis.util.PBConstants;
import cy.jdkdigital.productivebees.ProductiveBees;
import cy.jdkdigital.productivebees.init.ModDataComponents;
import cy.jdkdigital.productivebees.init.ModItems;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;

import java.util.ArrayList;
import java.util.Collection;

/**
	 * 创造模式物品栏事件处理器
	 * <br/>
	 * 根据万象创世蜜蜂的启用状态动态调整创造模式物品栏内容：
	 * <ul>
	 *   <li>启用时：向 PB 创造栏添加万象创世蜜脾和蜜脾块（createComb=false 时 PB 不会自动添加）</li>
	 *   <li>禁用时：从 PB 标签页和刷怪蛋标签页移除万象创世刷怪蛋</li>
	 * </ul>
	 * <p>
	 * 原理：拦截 {@link BuildCreativeModeTabContentsEvent} 事件，
	 * 启用时使用 {@link BuildCreativeModeTabContentsEvent#accept} 添加物品，
	 * 禁用时使用 {@link BuildCreativeModeTabContentsEvent#remove} 移除物品。
	 */
@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID)
public final class CreativeTabEventHandler {

	private CreativeTabEventHandler() {}

	/**
	 * 拦截创造模式物品栏内容构建事件
	 * <br/>
	 * 根据配置决定操作：
	 * <ul>
	 *   <li>万象创世启用：向 PB 创造栏添加蜜脾和蜜脾块</li>
	 *   <li>万象创世禁用：从创造栏移除万象创世刷怪蛋</li>
	 * </ul>
	 */
	@SubscribeEvent
	public static void onBuildCreativeModeTabContents(BuildCreativeModeTabContentsEvent event) {
		// 检查配置是否加载
		if (!ModConfig.areServerSpecsLoaded()) {
			return; // 配置未加载，不干预（保持默认行为）
		}

		if (ModConfig.SERVER.myriadCreationsEnabled.get()) {
			// 万象创世启用：向 PB 创造栏添加蜜脾和蜜脾块
			if (event.getTabKey().equals(ProductiveBees.TAB_KEY)) {
				addMyriadCreationsCombs(event);
			}
			return; // 启用时不执行移除逻辑
		}

		// 万象创世禁用：只处理 ProductiveBees 的标签页和刷怪蛋标签页
		if (!event.getTabKey().equals(ProductiveBees.TAB_KEY)
				&& !event.getTabKey().equals(net.minecraft.world.item.CreativeModeTabs.SPAWN_EGGS)) {
			return;
		}

		// 获取万象创世蜜蜂的刷怪蛋物品
		ResourceLocation myriadSpawnEggId = ResourceLocation.fromNamespaceAndPath(
				PBConstants.PRODUCTIVE_BEES_MOD_ID, "spawn_egg_" + PBConstants.MYRIADCREATIONS_TYPE.getPath());
		var myriadSpawnEgg = BuiltInRegistries.ITEM.get(myriadSpawnEggId);

		// 从父标签页中移除万象创世刷怪蛋
		removeSpawnEggsFromList(event.getParentEntries(), myriadSpawnEgg, event,
			CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
		// 从搜索标签页中移除万象创世刷怪蛋
		removeSpawnEggsFromList(event.getSearchEntries(), myriadSpawnEgg, event,
			CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
	}

	/**
	 * 向 PB 创造栏添加万象创世蜜脾和蜜脾块
	 * <br/>
	 * 当 createComb=false 时，PB 不会自动将万象创世蜜脾加入创造栏，需在此手动构造
	 * 携带 bee_type 数据组件的物品栈并添加。PB 的 configurable_honeycomb /
	 * configurable_comb_block 物品据此组件识别蜜脾种类，使其在创造栏和 JEI 中正确显示。
	 * <p>
	 * 重复添加保护：PB 创造栏可能已包含同物品但不同 bee_type 的条目（或相同 bee_type），
	 * 直接 event.accept 会触发 "already exists in the tab's list" 异常。
	 * 此处先遍历已有条目，匹配 ItemStack.matches（含组件比较），仅在不存在时添加。
	 */
	private static void addMyriadCreationsCombs(BuildCreativeModeTabContentsEvent event) {
		ItemStack honeycomb = new ItemStack(ModItems.CONFIGURABLE_HONEYCOMB.get());
		honeycomb.set(ModDataComponents.BEE_TYPE.get(), PBConstants.MYRIADCREATIONS_TYPE);
		safeAccept(event, honeycomb);

		ItemStack combBlock = new ItemStack(ModItems.CONFIGURABLE_COMB_BLOCK.get());
		combBlock.set(ModDataComponents.BEE_TYPE.get(), PBConstants.MYRIADCREATIONS_TYPE);
		safeAccept(event, combBlock);
	}

	/**
	 * 安全添加物品栈到创造栏 — 先检查是否已存在相同条目（含数据组件），避免重复添加异常
	 */
	private static void safeAccept(BuildCreativeModeTabContentsEvent event, ItemStack stack) {
		// 检查父标签页和搜索标签页是否已存在相同条目
		if (containsStack(event.getParentEntries(), stack) || containsStack(event.getSearchEntries(), stack)) {
			return;
		}
		event.accept(stack);
	}

	/**
	 * 检查条目列表是否已包含指定物品栈（含数据组件精确匹配）
	 */
	private static boolean containsStack(Collection<ItemStack> entries, ItemStack target) {
		for (ItemStack entry : entries) {
			if (ItemStack.matches(entry, target)) {
				return true;
			}
		}
		return false;
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
		// 遍历副本，避免 event.remove() 触发 ConcurrentModificationException
		for (ItemStack stack : new ArrayList<>(entries)) {
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
					var nbt = entityData.copyTag();
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
