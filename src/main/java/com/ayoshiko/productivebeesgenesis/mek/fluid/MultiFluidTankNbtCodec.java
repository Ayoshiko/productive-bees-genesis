package com.ayoshiko.productivebeesgenesis.mek.fluid;

import com.ayoshiko.productivebeesgenesis.mek.MekCentrifugeNbtKeys;
import com.ayoshiko.productivebeesgenesis.util.DevLog;
import mekanism.api.Action;
import mekanism.api.AutomationType;
import mekanism.api.fluid.IExtendedFluidTank;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.neoforged.neoforge.fluids.FluidStack;

/**
	 * 多流体槽 NBT 序列化/反序列化 Codec
	 * <br/>
	 * <b>设计原则(SRP)：</b>将 NBT 持久化逻辑从 {@link MultiFluidTankHolder} 抽离,
	 * 使 Holder 专注于槽位路由与生命周期管理,Codec 专注于序列化格式。
	 * 控制 Holder 行数 ≤500,符合项目规范。
	 * <p>
	 * <b>访问权限：</b>通过 {@link MultiFluidTankHolder} 的 package-private getter
	 * 访问内部字段(tanksInOrder / tanksByFluidKey / emptyTankCount),保持封装性。
	 *
	 * @since 1.0.0
	 */
public final class MultiFluidTankNbtCodec {

	/** 工具类禁止实例化 */
	private MultiFluidTankNbtCodec() {
	}

	/**
	 * 将所有非空槽位的 FluidStack 序列化到 NBT
	 * <br/>
	 * <b>设计原理：</b>
	 * <ul>
	 *   <li>空槽(getFluid().isEmpty())跳过不保存,减少 NBT 大小</li>
	 *   <li>重建时通过 getTankForInsert 自动路由到未映射空槽,建立 FluidKey 映射</li>
	 *   <li>不保存 componentsHash:getTankForInsert 基于完整 FluidStack 找槽,
	 *       重建时通过 FluidKey.of(stack) 重新计算</li>
	 * </ul>
	 *
	 * @param holder   多流体槽管理器
	 * @param nbt      目标 NBT(写入到 {@link MekCentrifugeNbtKeys#NBT_KEY_MULTI_FLUID_TANKS} 键下)
	 * @param provider 注册表访问器(FluidStack.save 需要)
	 */
	public static void writeToNBT(MultiFluidTankHolder holder, CompoundTag nbt, HolderLookup.Provider provider) {
		CompoundTag root = new CompoundTag();
		root.putInt("count", holder.getTanksInOrderForCodec().size());
		ListTag list = new ListTag();
		long totalFluidAmount = 0;
		for (IExtendedFluidTank tank : holder.getTanksInOrderForCodec()) {
			FluidStack fluid = tank.getFluid();
			if (!fluid.isEmpty()) {
				CompoundTag entry = new CompoundTag();
				entry.put("fluidStack", fluid.save(provider));
				list.add(entry);
				totalFluidAmount += fluid.getAmount();
			}
		}
		root.put("tanks", list);
		nbt.put(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, root);
	}

	/**
	 * 从 NBT 反序列化并恢复槽位内容
	 * <br/>
	 * <b>重建原理(预分配后)：</b>
	 * <ol>
	 *   <li>清空 tanksByFluidKey 映射 + 重置 emptyTankCount = maxTanks</li>
	 *   <li>遍历 tanksInOrder 对每个预分配槽调用 setStack(FluidStack.EMPTY) 清空内容
	 *       (保留槽位结构,不 clear tanksInOrder)</li>
	 *   <li>遍历 NBT 中的 ListTag "tanks",对每个 entry 解析 FluidStack</li>
	 *   <li>调用 getTankForInsert 路由到未映射空槽(预分配),建立映射并 emptyTankCount 递减</li>
	 *   <li>调用 tank.insert 填充内容,容量不足时记录警告并继续(不中断循环)</li>
	 * </ol>
	 * <p>
	 * <b>不 clear tanksInOrder 的原因：</b>预分配的槽位结构固定,
	 * clear 会导致槽位数量减少,引发 MEK DataSlot 索引偏移。
	 *
	 * @param holder   多流体槽管理器
	 * @param nbt      源 NBT(从 {@link MekCentrifugeNbtKeys#NBT_KEY_MULTI_FLUID_TANKS} 键读取)
	 * @param provider 注册表访问器(FluidStack.parseOptional 需要)
	 */
	public static void readFromNBT(MultiFluidTankHolder holder, CompoundTag nbt, HolderLookup.Provider provider) {
		if (!nbt.contains(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS, Tag.TAG_COMPOUND)) {
			return;
		}
		// 清空映射 + 重置空槽计数 + 清空所有预分配槽位内容(保留槽位结构)
		holder.getTanksByFluidKeyForCodec().clear();
		holder.getEmptyTankCountForCodec().set(holder.getMaxTanks());
		for (IExtendedFluidTank tank : holder.getTanksInOrderForCodec()) {
			tank.setStack(FluidStack.EMPTY);
		}
		CompoundTag root = nbt.getCompound(MekCentrifugeNbtKeys.NBT_KEY_MULTI_FLUID_TANKS);
		ListTag list = root.getList("tanks", Tag.TAG_COMPOUND);
		long totalFluidAmount = 0;
		for (int i = 0; i < list.size(); i++) {
			CompoundTag entry = list.getCompound(i);
			FluidStack stack = FluidStack.parseOptional(provider, entry.getCompound("fluidStack"));
			if (stack.isEmpty()) {
				continue; // 解析失败跳过,不影响其他槽位
			}
			// getTankForInsert 路由到未映射空槽(预分配),建立 FluidKey → 槽 映射
			IExtendedFluidTank tank = holder.getTankForInsert(stack);
			if (tank == null) {
				continue; // 无可用空槽,跳过
			}
			// 检查 insert 返回值 — 容量不足时记录警告,继续处理其他槽位(不中断循环)
			// 触发场景：等级升级降级时容量减小,超出新容量的流体被丢弃
			FluidStack remaining = tank.insert(stack, Action.EXECUTE, AutomationType.INTERNAL);
			if (!remaining.isEmpty()) {
				DevLog.warn("fluid_tank", "流体槽容量不足,丢弃 {} mB 流体 {}", remaining.getAmount(), remaining.getFluid());
			}
			totalFluidAmount += stack.getAmount() - remaining.getAmount();
		}
	}
}
