package com.ayoshiko.productivebeesgenesis.mek.ae2;

import appeng.api.stacks.AEKey;
import appeng.api.stacks.AEKeyType;
import appeng.api.stacks.GenericStack;
import com.ayoshiko.productivebeesgenesis.resource.ResourceKey;
import com.ayoshiko.productivebeesgenesis.resource.ResourceStack;
import com.ayoshiko.productivebeesgenesis.resource.ResourceTypes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.ItemStack;

/** AE2 已注册键与无外部类型的资源堆栈互转；仅在 AE2 加载守卫后使用。 */
public final class Ae2GenericResourceBridge {
	private Ae2GenericResourceBridge() {}

	/** 使用指定类型的 codec，严格回读完整数据，避免通用 AEKey codec 的缺失内容回退。 */
	public static AEKey resolve(HolderLookup.Provider registries, ResourceKey resource) {
		var typeId = ResourceTypes.aeKeyType(resource);
		if (typeId == null) return null;
		var type = appeng.api.stacks.AEKeyTypes.get(typeId);
		if (type == null || !type.getId().equals(typeId)) return null;
		var payload = resource.payload();
		var data = payload.getCompound("data");
		var key = type.loadKeyFromTag( data.copy());
		if (key == null || key.getType() != type || !type.contains(key)
				|| !key.getId().toString().equals(payload.getString("id"))
				) return null;
		var canonical = key.toTag();
		if (canonical == null || !canonical.equals(data)) return null;
		var restored = type.loadKeyFromTag( canonical.copy());
		return restored != null && key.equals(restored) && restored.equals(key) ? key : null;
	}

	/** 包裹真实 GenericStack，保留完整组件与 long 数量；不修改原栈。 */
	public static ResourceStack fromAeStack(HolderLookup.Provider registries, GenericStack stack) {
		if (stack == null || stack.what() == null) throw new IllegalArgumentException("Missing AE stack");
		var key = stack.what();
		var resource = ResourceKey.aeKey(key.getType().getId(), key.getId(), key.toTag());
		if (resolve(registries, resource) == null) throw new IllegalArgumentException("AE key cannot round-trip exactly");
		return new ResourceStack(resource, stack.amount());
	}

	/** 解包用于 ME 交接或显示；解析失败时保留调用方原始资源堆栈。 */
	public static GenericStack toAeStack(HolderLookup.Provider registries, ResourceStack stack) {
		var key = Ae2ResourceKeyBridge.resolve(registries, stack.key());
		if (key == null) throw new IllegalArgumentException("Resource AE key is unavailable");
		return new GenericStack(key, stack.amount());
	}

	/** 从一件 AE2 包裹物品读取配方模板，不消费物品或给账户入账。 */
	public static ResourceStack fromWrappedItem(HolderLookup.Provider registries, ItemStack template) {
		if (template.getCount() != 1 || !GenericStack.isWrapped(template)) {
			throw new IllegalArgumentException("Expected one wrapped AE stack template");
		}
		return fromAeStack(registries, GenericStack.unwrapItemStack(template));
	}

	/** 只构造显示／筛选模板；生产资产继续保留在账户，不生成世界掉落。 */
	public static ItemStack wrapForDisplay(HolderLookup.Provider registries, ResourceStack stack) {
		return GenericStack.wrapInItemStack(toAeStack(registries, stack));
	}
}
