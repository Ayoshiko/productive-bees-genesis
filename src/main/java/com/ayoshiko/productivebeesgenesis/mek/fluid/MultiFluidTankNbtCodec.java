package com.ayoshiko.productivebeesgenesis.mek.fluid;

import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeNbtKeys;
import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** 多流体恢复先完成解码和容量布局，再一次替换内容与索引。 */
public final class MultiFluidTankNbtCodec {
	private MultiFluidTankNbtCodec() {
	}

	public static void writeToNBT(MultiFluidTankHolder holder, CompoundTag nbt, HolderLookup.Provider provider) {
		Tag unresolved = holder.getUnresolvedSavedData();
		if (unresolved != null) {
			nbt.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, unresolved.copy());
			return;
		}
		CompoundTag root = new CompoundTag();
		root.putInt("count", holder.getTanksInOrderForCodec().size());
		ListTag list = new ListTag();
		for (IExtendedFluidTank tank : holder.getTanksInOrderForCodec()) {
			FluidStack fluid = tank.getFluid();
			if (fluid.isEmpty()) continue;
			CompoundTag entry = new CompoundTag();
			entry.put("fluidStack", fluid.save(provider));
			list.add(entry);
		}
		root.put("tanks", list);
		nbt.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, root);
	}

	public static void readFromNBT(MultiFluidTankHolder holder, CompoundTag nbt, HolderLookup.Provider provider) {
		Tag raw = nbt.get(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS);
		if (raw == null) return;
		try {
			List<FluidStack> saved = decode(raw, provider);
			FluidStack[] layout = plan(saved, holder.getTanksInOrderForCodec());
			holder.restoreLayout(layout);
		} catch (RuntimeException invalid) {
			holder.preserveUnresolvedSavedData(raw);
		}
	}

	/** 安装器也使用相同的完整解码；缺失注册或未知字段不能当空库存。 */
	public static List<FluidStack> decode(Tag raw, HolderLookup.Provider provider) {
		if (!(raw instanceof CompoundTag root) || !Set.of("count", "tanks").containsAll(root.getAllKeys())
				|| !(root.get("tanks") instanceof ListTag entries)
				|| !entries.isEmpty() && entries.getElementType() != Tag.TAG_COMPOUND)
			throw new IllegalArgumentException("Invalid stored fluid inventory");
		if (root.contains("count")) {
			if (!root.contains("count", Tag.TAG_INT) || root.getInt("count") < 0)
				throw new IllegalArgumentException("Invalid stored fluid count");
		} else if (entries.size() != 1) {
			// 已发布的基础机升级快照仅省略单个非空罐的 count。
			throw new IllegalArgumentException("Invalid legacy single-tank inventory");
		}
		List<FluidStack> saved = new ArrayList<>(entries.size());
		for (Tag value : entries) {
			CompoundTag entry = (CompoundTag) value;
			if (!Set.of("fluidStack").containsAll(entry.getAllKeys()) || !entry.contains("fluidStack", Tag.TAG_COMPOUND))
				throw new IllegalArgumentException("Invalid stored fluid entry");
			saved.add(FluidStack.parse(provider, entry.getCompound("fluidStack")).orElseThrow());
		}
		return saved;
	}

	/** 单个保存条目可跨多个罐；完整组件相同才合并，不经 int 总量累加。 */
	public static FluidStack[] plan(List<FluidStack> saved, List<IExtendedFluidTank> tanks) {
		FluidStack[] layout = new FluidStack[tanks.size()];
		java.util.Arrays.fill(layout, FluidStack.EMPTY);
		for (FluidStack stack : saved) {
			long remaining = stack.getAmount();
			for (int pass = 0; pass < 2 && remaining > 0; pass++) {
				for (int index = 0; index < layout.length && remaining > 0; index++) {
					FluidStack current = layout[index];
					if (pass == 0 && (current.isEmpty() || !FluidStack.isSameFluidSameComponents(current, stack))) continue;
					if (pass == 1 && !current.isEmpty()) continue;
					int held = current.isEmpty() ? 0 : current.getAmount();
					int amount = (int) Math.min(remaining, Math.max(0L, (long) tanks.get(index).getCapacity() - held));
					if (amount <= 0) continue;
					layout[index] = stack.copyWithAmount(held + amount);
					remaining -= amount;
				}
			}
			if (remaining > 0) throw new IllegalArgumentException("Stored fluid exceeds target capacity");
		}
		return layout;
	}
}
