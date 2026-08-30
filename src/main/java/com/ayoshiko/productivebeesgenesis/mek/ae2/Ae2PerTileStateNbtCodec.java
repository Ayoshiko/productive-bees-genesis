package com.ayoshiko.productivebeesgenesis.mek.ae2;

import net.minecraft.nbt.CompoundTag;

/**
 * AE2 per-tile 状态 NBT 编解码（纯静态，无状态）
 * <br/>
 * 从 {@link Ae2OutputStateHolder} 拆分而来，职责（SRP）：per-tile AE2 开关
 * 与输入过滤器状态的持久化格式编解码，键名统一使用
 * {@code productivebeesgenesis_} 前缀避免与其他模组 NBT 键冲突。
 */
final class Ae2PerTileStateNbtCodec {

	private Ae2PerTileStateNbtCodec() {
	}

	/**
	 * 保存 per-tile 状态到 NBT
	 *
	 * @param holder 状态持有者
	 * @param tag    目标 NBT 标签
	 */
	static void save(Ae2OutputStateHolder holder, CompoundTag tag) {
		tag.putBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT, holder.isAeItemOutputEnabled());
		tag.putBoolean(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT, holder.isAeFluidOutputEnabled());
		tag.putBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_INPUT, holder.isAeItemInputEnabled());
		tag.putBoolean(Ae2NbtKeys.NBT_KEY_AE_INPUT_NBT_IGNORE, holder.isAeInputNbtIgnore());
		tag.putBoolean(Ae2NbtKeys.NBT_KEY_SMELTING_COMPAT, holder.isSmeltingCompatEnabled());
		tag.putBoolean(Ae2NbtKeys.NBT_KEY_CENTRIFUGE_DIRECT_AE_OUTPUT, holder.isCentrifugeDirectAeOutputEnabled());
		// 过滤器状态序列化到子标签，避免与 per-tile 开关键名冲突
		Ae2InputFilter filter = holder.getAeInputFilter();
		if (filter != null) {
			CompoundTag filterTag = new CompoundTag();
			filter.save(filterTag);
			tag.put(Ae2NbtKeys.NBT_KEY_AE_INPUT_FILTER, filterTag);
		}
		// 标签过滤表达式独立子标签；无配置时 save 不写任何键，子标签为空则不落盘
		CompoundTag tagFilterTag = new CompoundTag();
		holder.getAeTagFilter().save(tagFilterTag);
		if (!tagFilterTag.isEmpty()) {
			tag.put(Ae2NbtKeys.NBT_KEY_AE_INPUT_TAG_FILTER, tagFilterTag);
		}
	}

	/**
	 * 从 NBT 加载 per-tile 状态
	 * <br/>
	 * 注意：getBoolean 在键不存在时返回 false，但物品/流体输出默认值均为 true，
	 * 故对两个键均使用 contains 检查回退默认值 true。
	 *
	 * @param holder 状态持有者
	 * @param tag    源 NBT 标签
	 */
	static void load(Ae2OutputStateHolder holder, CompoundTag tag) {
		holder.setAeItemOutputEnabled(tag.contains(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT)
				? tag.getBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_OUTPUT) : true);
		holder.setAeFluidOutputEnabled(tag.contains(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT)
				? tag.getBoolean(Ae2NbtKeys.NBT_KEY_AE_FLUID_OUTPUT) : true);
		// 输入拉取开关默认 false（与字段声明一致），旧存档无此键时回退 false
		holder.setAeItemInputEnabled(tag.contains(Ae2NbtKeys.NBT_KEY_AE_ITEM_INPUT)
				? tag.getBoolean(Ae2NbtKeys.NBT_KEY_AE_ITEM_INPUT) : false);
		// NBT 忽略开关默认 true（与字段声明一致），旧存档无此键时回退 true
		holder.setAeInputNbtIgnore(tag.contains(Ae2NbtKeys.NBT_KEY_AE_INPUT_NBT_IGNORE)
				? tag.getBoolean(Ae2NbtKeys.NBT_KEY_AE_INPUT_NBT_IGNORE) : true);
		// 熔炉配方兼容开关默认 false（与字段声明一致），旧存档无此键时回退 false
		holder.setSmeltingCompatEnabled(tag.contains(Ae2NbtKeys.NBT_KEY_SMELTING_COMPAT)
				? tag.getBoolean(Ae2NbtKeys.NBT_KEY_SMELTING_COMPAT) : false);
		holder.setCentrifugeDirectAeOutputEnabled(tag.contains(Ae2NbtKeys.NBT_KEY_CENTRIFUGE_DIRECT_AE_OUTPUT)
				? tag.getBoolean(Ae2NbtKeys.NBT_KEY_CENTRIFUGE_DIRECT_AE_OUTPUT) : false);
		// 标签过滤：无子标签的旧存档重置为空表达式，保持与 clear() 一致的默认值。
		// 本类不依赖 AE2，故不受 Issue #8 类加载守卫约束，可无条件加载。
		if (tag.contains(Ae2NbtKeys.NBT_KEY_AE_INPUT_TAG_FILTER)) {
			holder.getAeTagFilter().load(tag.getCompound(Ae2NbtKeys.NBT_KEY_AE_INPUT_TAG_FILTER));
		} else {
			holder.getAeTagFilter().reset();
		}
		// 过滤器状态反序列化（旧存档兼容：无此键时创建空过滤器）
		// AE2 未安装时跳过：无 AE2 环境不应创建过滤器（方块实体从存档恢复即触发构造，
		// Issue #8 类加载安全）；save 侧用 getAeInputFilter() 不创建，天然对称
		if (Ae2IntegrationLoader.isAe2Loaded()) {
			Ae2InputFilter filter = holder.getOrCreateInputFilter();
			if (tag.contains(Ae2NbtKeys.NBT_KEY_AE_INPUT_FILTER)) {
				filter.load(tag.getCompound(Ae2NbtKeys.NBT_KEY_AE_INPUT_FILTER));
			} else {
				filter.resetPersistentState();
			}
		}
	}

	/** 保存不应进入配置卡的 AE2 输入剩余物所有权记录。 */
	static void savePendingItems(Ae2OutputStateHolder holder, CompoundTag tag) {
		holder.getPendingItemBuffer().save(tag);
		holder.getOutputLedger().save(tag);
	}

	/** 加载方块实体的 AE2 输入剩余物所有权记录。 */
	static void loadPendingItems(Ae2OutputStateHolder holder, CompoundTag tag) {
		holder.getPendingItemBuffer().load(tag);
		holder.getOutputLedger().load(tag);
	}
}
