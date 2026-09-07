package com.ayoshiko.productivebeesgenesis.util;

import com.ayoshiko.productivebeesgenesis.apiary.IPbUpgradeProvider;
import com.ayoshiko.productivebeesgenesis.apiary.PbUpgradeType;
import com.ayoshiko.productivebeesgenesis.init.ModItems;
import cy.jdkdigital.productivelib.common.block.entity.InventoryHandlerHelper.BlockEntityItemStackHandler;
import mekanism.api.recipes.ItemStackToItemStackRecipe;
import mekanism.common.recipe.MekanismRecipeType;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SingleRecipeInput;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 粗矿熔炼升级的判定、配方缓存和输出转换工具。
 * <p>
 * 只有同时满足 raw_materials/raw_ores 标签、Mekanism 或原版熔炼配方以及 ingots 输出标签的物品才会转换。
 * 这样不会把食物、木炭或其他普通可熔炼物误当作粗矿；整合包缺少标签时仍可通过补充 common/forge 标签兼容。
 * <p>
 * 服务端 tick 只访问本类的不可变快照和并发缓存；配方重载时由主类调用 {@link #invalidateCache()}。
 */
public final class RawOreSmeltingUpgradeHelper {

	private static final TagKey<Item> COMMON_RAW_MATERIALS = itemTag("c", "raw_materials");
	private static final TagKey<Item> COMMON_RAW_ORES = itemTag("c", "raw_ores");
	private static final TagKey<Item> FORGE_RAW_MATERIALS = itemTag("forge", "raw_materials");
	private static final TagKey<Item> FORGE_RAW_ORES = itemTag("forge", "raw_ores");
	private static final TagKey<Item> COMMON_INGOTS = itemTag("c", "ingots");
	private static final TagKey<Item> FORGE_INGOTS = itemTag("forge", "ingots");

	private static final List<TagKey<Item>> RAW_TAGS = List.of(
			COMMON_RAW_MATERIALS, COMMON_RAW_ORES, FORGE_RAW_MATERIALS, FORGE_RAW_ORES);
	private static final List<TagKey<Item>> INGOT_TAGS = List.of(COMMON_INGOTS, FORGE_INGOTS);
	/** 熔炼配方只按物品类型匹配，按 Item 缓存可避免带动态组件的物品造成无界 key 增长。 */
	private static final ConcurrentHashMap<Item, Optional<Conversion>> CONVERSIONS =
			new ConcurrentHashMap<>();

	private RawOreSmeltingUpgradeHelper() {
	}

	/** 判断方块实体是否安装了粗矿熔炼升级。 */
	public static boolean hasUpgrade(BlockEntity blockEntity) {
		if (blockEntity instanceof IPbUpgradeProvider provider) {
			return provider.getPbUpgradeInstalledCount(PbUpgradeType.RAW_ORE_SMELTING) > 0;
		}
		return blockEntity instanceof cy.jdkdigital.productivelib.common.block.entity.IUpgradeableBlockEntity upgradeable
				&& upgradeable.getUpgradeCount(ModItems.RAW_ORE_SMELTING_UPGRADE.get()) > 0;
	}

	/** 将 pending 输出按熔炼配方转换；无变化时返回 false。 */
	public static boolean convertPendingOutputs(Level level, Map<ItemStack, Integer> outputs) {
		if (level == null || outputs == null || outputs.isEmpty()) return false;
		List<ItemStack> source = new ArrayList<>(outputs.size());
		for (Map.Entry<ItemStack, Integer> entry : outputs.entrySet()) {
			int count = Math.max(0, entry.getValue());
			if (count > 0) source.add(entry.getKey().copyWithCount(count));
		}
		if (source.isEmpty()) return false;
		List<ItemStack> converted = convert(level, source);
		if (sameStacks(source, converted)) return false;
		outputs.clear();
		for (ItemStack stack : converted) {
			if (!stack.isEmpty()) outputs.put(stack.copyWithCount(stack.getCount()), stack.getCount());
		}
		return true;
	}

	/** 将一组输出转换为对应锭，数量不足一组时保留粗矿余数。 */
	public static List<ItemStack> convert(Level level, List<ItemStack> drops) {
		if (level == null || drops == null || drops.isEmpty()) return drops;
		List<ItemStack> result = new ArrayList<>(drops.size());
		for (ItemStack stack : drops) {
			if (stack == null || stack.isEmpty()) continue;
			Conversion conversion = findConversion(level, stack).orElse(null);
			if (conversion == null) {
				addAmount(result, stack, stack.getCount());
				continue;
			}
			long crafts = stack.getCount() / (long) conversion.inputCount();
			long remainder = stack.getCount() % conversion.inputCount();
			if (crafts > 0) {
				addAmount(result, conversion.result(), crafts * conversion.result().getCount());
			}
			if (remainder > 0) addAmount(result, stack, remainder);
		}
		return result;
	}

	/** 转换原生资源蜜蜂离心机已写入的输出槽，容量不足时保持原库存不变。 */
	public static boolean convertStored(Level level, IItemHandler handler) {
		if (level == null || level.isClientSide() || !(handler instanceof IItemHandlerModifiable modifiable)) {
			return false;
		}
		int[] slots = resolveOutputSlots(handler);
		if (slots.length == 0) return false;
		List<ItemStack> source = new ArrayList<>(slots.length);
		for (int slot : slots) {
			ItemStack stack = handler.getStackInSlot(slot);
			if (!stack.isEmpty()) addAmount(source, stack, stack.getCount());
		}
		if (source.isEmpty()) return false;
		List<ItemStack> replacement = convert(level, source);
		if (sameStacks(source, replacement) || !canFit(handler, slots, replacement)) return false;
		ItemStack[] snapshot = new ItemStack[slots.length];
		for (int i = 0; i < slots.length; i++) snapshot[i] = handler.getStackInSlot(slots[i]).copy();
		for (int slot : slots) handler.extractItem(slot, handler.getStackInSlot(slot).getCount(), false);
		if (insertAll(handler, slots, replacement)) return true;
		for (int i = 0; i < slots.length; i++) modifiable.setStackInSlot(slots[i], snapshot[i]);
		return false;
	}

	/** 清空熔炼配方缓存，服务器停止或数据重载时调用。 */
	public static void invalidateCache() {
		CONVERSIONS.clear();
	}

	/** 供单元测试验证数量换算边界。 */
	static long convertedCount(int inputCount, int recipeInput, int recipeOutput) {
		if (inputCount <= 0 || recipeInput <= 0 || recipeOutput <= 0) return inputCount;
		return (inputCount / (long) recipeInput) * recipeOutput;
	}

	private static Optional<Conversion> findConversion(Level level, ItemStack stack) {
		if (level == null || stack == null || stack.isEmpty() || !isRawMaterial(stack)) return Optional.empty();
		Item key = stack.getItem();
		return CONVERSIONS.computeIfAbsent(key, ignored -> resolveConversion(level, stack));
	}

	private static Optional<Conversion> resolveConversion(Level level, ItemStack input) {
		ItemStackToItemStackRecipe recipe = MekanismRecipeType.SMELTING.getInputCache()
				.findFirstRecipe(level, input);
		if (recipe != null) {
			ItemStack output = recipe.getOutput(input);
			long needed = recipe.getInput().getNeededAmount(input);
			if (isValidOutput(input, output) && needed > 0 && needed <= Integer.MAX_VALUE) {
				return Optional.of(new Conversion((int) needed, output.copy()));
			}
		}

		// 原版金/铁/铜粗矿通常只有 Furnace/Blast Furnace 配方，作为 Mekanism 配方的兼容回退。
		Optional<RecipeHolder<SmeltingRecipe>> vanilla = level.getRecipeManager().getRecipeFor(
				RecipeType.SMELTING, new SingleRecipeInput(input), level);
		if (vanilla.isEmpty()) return Optional.empty();
		ItemStack output = vanilla.get().value().getResultItem(level.registryAccess());
		return isValidOutput(input, output)
				? Optional.of(new Conversion(1, output.copy())) : Optional.empty();
	}

	private static boolean isValidOutput(ItemStack input, ItemStack output) {
		return output != null && !output.isEmpty()
				&& !ItemStack.isSameItemSameComponents(input, output)
				&& isIngot(output);
	}

	private static boolean isRawMaterial(ItemStack stack) {
		for (TagKey<Item> tag : RAW_TAGS) {
			if (stack.is(tag)) return true;
		}
		return false;
	}

	private static boolean isIngot(ItemStack stack) {
		for (TagKey<Item> tag : INGOT_TAGS) {
			if (stack.is(tag)) return true;
		}
		return false;
	}

	private static int[] resolveOutputSlots(IItemHandler handler) {
		if (handler instanceof BlockEntityItemStackHandler blockHandler) {
			int[] slots = blockHandler.getOutputSlots();
			return slots == null ? new int[0] : slots;
		}
		int[] slots = new int[handler.getSlots()];
		for (int i = 0; i < slots.length; i++) slots[i] = i;
		return slots;
	}

	private static boolean canFit(IItemHandler handler, int[] slots, List<ItemStack> replacement) {
		ItemStack[] simulated = new ItemStack[slots.length];
		// 转换流程会先清空目标输出槽，再写入 replacement；模拟也必须从空槽开始，
		// 否则满载粗矿槽会被错误判定为无法放入等量金属锭。
		for (int i = 0; i < slots.length; i++) simulated[i] = ItemStack.EMPTY;
		for (ItemStack stack : replacement) {
			int remaining = stack.getCount();
			for (int i = 0; i < simulated.length && remaining > 0; i++) {
				ItemStack current = simulated[i];
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
				int capacity = Math.min(Math.max(1, handler.getSlotLimit(slots[i])), current.getMaxStackSize());
				int accepted = Math.min(remaining, Math.max(0, capacity - current.getCount()));
				current.grow(accepted);
				remaining -= accepted;
			}
			for (int i = 0; i < simulated.length && remaining > 0; i++) {
				if (!simulated[i].isEmpty()) continue;
				int capacity = Math.min(Math.max(1, handler.getSlotLimit(slots[i])), stack.getMaxStackSize());
				int accepted = Math.min(remaining, capacity);
				simulated[i] = stack.copyWithCount(accepted);
				remaining -= accepted;
			}
			if (remaining > 0) return false;
		}
		return true;
	}

	private static boolean insertAll(IItemHandler handler, int[] slots, List<ItemStack> stacks) {
		for (ItemStack stack : stacks) {
			int remaining = stack.getCount();
			for (int slot : slots) {
				if (remaining <= 0) break;
				ItemStack current = handler.getStackInSlot(slot);
				if (current.isEmpty() || !ItemStack.isSameItemSameComponents(current, stack)) continue;
				int offered = Math.min(remaining,
						Math.min(Math.max(1, handler.getSlotLimit(slot)), stack.getMaxStackSize()));
				ItemStack remainder = handler.insertItem(slot, stack.copyWithCount(offered), false);
				remaining -= offered - remainder.getCount();
			}
			for (int slot : slots) {
				if (remaining <= 0 || !handler.getStackInSlot(slot).isEmpty()) continue;
				int offered = Math.min(remaining,
						Math.min(Math.max(1, handler.getSlotLimit(slot)), stack.getMaxStackSize()));
				ItemStack remainder = handler.insertItem(slot, stack.copyWithCount(offered), false);
				remaining -= offered - remainder.getCount();
			}
			if (remaining > 0) return false;
		}
		return true;
	}

	private static boolean sameStacks(List<ItemStack> left, List<ItemStack> right) {
		if (left.size() != right.size()) return false;
		for (int i = 0; i < left.size(); i++) {
			if (!ItemStack.isSameItemSameComponents(left.get(i), right.get(i))
					|| left.get(i).getCount() != right.get(i).getCount()) return false;
		}
		return true;
	}

	private static void addAmount(List<ItemStack> stacks, ItemStack template, long amount) {
		if (template == null || template.isEmpty() || amount <= 0) return;
		for (ItemStack existing : stacks) {
			if (!ItemStack.isSameItemSameComponents(existing, template)) continue;
			int accepted = (int) Math.min(amount, Integer.MAX_VALUE - (long) existing.getCount());
			existing.grow(accepted);
			amount -= accepted;
			if (amount <= 0) return;
		}
		while (amount > 0) {
			int count = (int) Math.min(amount, Integer.MAX_VALUE);
			stacks.add(template.copyWithCount(count));
			amount -= count;
		}
	}

	private static TagKey<Item> itemTag(String namespace, String path) {
		return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
	}

	private record Conversion(int inputCount, ItemStack result) {
		private Conversion {
			result = result.copy();
		}
	}
}
