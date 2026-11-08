package com.ayoshiko.productivebeesgenesis.recipe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ayoshiko.productivebeesgenesis.apiary.ApiaryPbUpgradeHandler;
import com.ayoshiko.productivebeesgenesis.apiary.ApiarySlotSerializer;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * 工作台合成升级高等级工厂时的 NBT 合并逻辑测试。
 * <p>
 * <b>为什么需要</b>：这条路径直接决定「机器内部有物品 / 输出槽有物品 / 装了升级」时能否
 * 完整带到新等级机器上。原实现用「首个输入的原始 NBT」作合并 base，而正常路径下
 * 输出物品上已经有 {@code MekanismShapedRecipe} 合并好的 {@code BLOCK_ENTITY_DATA}
 * —— 覆盖它就等于丢掉 MEK 已合并进来的第 2..N 个输入的物品栏（多输入升级场景静默丢物品）。
 * <p>
 * 本测试覆盖纯 NBT 逻辑（不依赖 Minecraft 运行时注册表），并额外用源码断言钉住
 * 「base 优先取输出已有数据」这一不变量。
 */
class ApiaryCraftingDataTransferTest {

	private static final String PB_UPGRADE_KEY = ApiaryPbUpgradeHandler.NBT_KEY_PB_UPGRADE_COUNTS;
	private static final String BEE_SLOTS_KEY = ApiarySlotSerializer.NBT_KEY_BEE_SLOTS;

	// ===== PB 升级数量合并 =====

	@Test
	@DisplayName("PB 升级数量从全部输入重新求和，与 base 已有内容无关")
	void pbUpgradeCountsSumAllInputsRegardlessOfBase() {
		List<CompoundTag> inputs = new ArrayList<>();
		inputs.add(machineTag(countsTag("productivebees:test_a", 2)));
		inputs.add(machineTag(countsTag("productivebees:test_a", 3)));
		inputs.add(machineTag(countsTag("productivebees:test_a", 4)));

		// base 里预置一个「错误的」旧值：必须被丢弃后从零求和，否则会重复计入
		CompoundTag merged = new CompoundTag();
		merged.put(PB_UPGRADE_KEY, countsTag("productivebees:test_a", 99));

		ApiaryCraftingDataTransfer.mergePbUpgradeCounts(merged, inputs, PB_UPGRADE_KEY);

		assertEquals(9, merged.getCompound(PB_UPGRADE_KEY).getInt("productivebees:test_a"),
				"三个输入各 2/3/4 必须求和为 9，且不得把 base 里的 99 当成首输入重复计入");
	}

	@Test
	@DisplayName("PB 升级缺失兼容，负数拒绝合并")
	void pbUpgradeCountsRejectNegativeCount() {
		List<CompoundTag> inputs = new ArrayList<>();
		inputs.add(machineTag(countsTag("productivebees:test_b", 0)));
		inputs.add(new CompoundTag()); // 无 PB 升级字段（老存档）
		inputs.add(machineTag(countsTag("productivebees:test_b", -5)));

		CompoundTag merged = new CompoundTag();
		assertThrows(IllegalArgumentException.class,
				() -> ApiaryCraftingDataTransfer.mergePbUpgradeCounts(merged, inputs, PB_UPGRADE_KEY));
		assertFalse(merged.contains(PB_UPGRADE_KEY));
	}

	@Test
	@DisplayName("PB 升级数量超出保存范围时拒绝合并")
	void pbUpgradeCountsRejectOverflow() {
		List<CompoundTag> inputs = new ArrayList<>();
		inputs.add(machineTag(countsTag("productivebees:test_c", Integer.MAX_VALUE)));
		inputs.add(machineTag(countsTag("productivebees:test_c", Integer.MAX_VALUE)));

		CompoundTag merged = new CompoundTag();
		assertThrows(ArithmeticException.class,
				() -> ApiaryCraftingDataTransfer.mergePbUpgradeCounts(merged, inputs, PB_UPGRADE_KEY));
		assertFalse(merged.contains(PB_UPGRADE_KEY));
	}

	@Test
	void installedGeneSamplersKeepCountsAboveEnumFallback() {
		CompoundTag merged = new CompoundTag();
		ApiaryCraftingDataTransfer.mergePbUpgradeCounts(merged, List.of(
				machineTag(countsTag("gene_sampler", 6)),
				machineTag(countsTag("gene_sampler", 6))), PB_UPGRADE_KEY);
		assertEquals(12, merged.getCompound(PB_UPGRADE_KEY).getInt("gene_sampler"));
	}

	// ===== 蜜蜂槽合并 =====

	@Test
	@DisplayName("蜜蜂槽不足时拒绝，足够时所有蜜蜂获得唯一新索引")
	void beeSlotsRejectOverflowAndReindexEveryInput() {
		List<CompoundTag> inputs = new ArrayList<>();
		inputs.add(beeSlotsTag("bee_0", "bee_1"));
		inputs.add(beeSlotsTag("bee_2", "bee_3"));
		inputs.add(beeSlotsTag("bee_4", "bee_5"));

		CompoundTag merged = new CompoundTag();
		assertThrows(IllegalArgumentException.class,
				() -> ApiaryCraftingDataTransfer.mergeBeeSlots(merged, inputs, 4));
		assertFalse(merged.contains(BEE_SLOTS_KEY));
		ApiaryCraftingDataTransfer.mergeBeeSlots(merged, inputs, 6);
		ListTag result = merged.getList(BEE_SLOTS_KEY, Tag.TAG_COMPOUND);
		assertEquals(6, result.size());
		for (int i = 0; i < result.size(); i++) {
			assertEquals(i, result.getCompound(i).getInt("slot_index"));
			assertEquals("bee_" + i, result.getCompound(i).getString("type"));
		}
		assertEquals(0, inputs.get(1).getList(BEE_SLOTS_KEY, Tag.TAG_COMPOUND).getCompound(0).getInt("slot_index"));
	}

	@Test
	@DisplayName("蜜蜂槽合并不受 base 已有内容影响（不重复计入首输入）")
	void beeSlotsMergeIgnoresPreexistingBaseContent() {
		List<CompoundTag> inputs = new ArrayList<>();
		inputs.add(beeSlotsTag("bee_0"));
		inputs.add(beeSlotsTag("bee_1"));

		// base 中预置首输入的副本：原实现会把它当作「首输入的蜜蜂」续接，导致重复计入
		CompoundTag merged = new CompoundTag();
		merged.put(BEE_SLOTS_KEY, beeSlots("bee_0"));

		ApiaryCraftingDataTransfer.mergeBeeSlots(merged, inputs, 16);

		ListTag result = merged.getList(BEE_SLOTS_KEY, Tag.TAG_COMPOUND);
		assertEquals(2, result.size(), "必须从零重建：base 里的副本不得与首输入重复计入");
		assertEquals("bee_0", result.getCompound(0).getString("type"));
		assertEquals("bee_1", result.getCompound(1).getString("type"));
	}

	@Test
	@DisplayName("蜜蜂槽容量为零时拒绝合成")
	void beeSlotsZeroCapacityRejects() {
		List<CompoundTag> inputs = new ArrayList<>();
		inputs.add(beeSlotsTag("bee_0", "bee_1"));

		CompoundTag merged = new CompoundTag();
		assertThrows(IllegalArgumentException.class,
				() -> ApiaryCraftingDataTransfer.mergeBeeSlots(merged, inputs, 0));
		assertFalse(merged.contains(BEE_SLOTS_KEY));
	}

	// ===== 源码级不变量 =====

	@Test
	@DisplayName("合并 base 必须优先沿用输出物品上 MEK 已合并好的数据")
	void mergeBasePrefersDestinationBlockEntityData() throws Exception {
		String source = Files.readString(Path.of(
				"src/main/java/com/ayoshiko/productivebeesgenesis/recipe/ApiaryCraftingDataTransfer.java"));
		// MEK 的 ItemRecipeData.merge 会把**每一个**输入的物品栏并进输出物品的
		// BLOCK_ENTITY_DATA；若这里改回「首个输入的原始 NBT」，多输入升级会静默丢失
		// 第 2..N 个机器里的物品。
		assertTrue(source.contains("CompoundTag merged = readBlockEntityData(dest);"),
				"mergeMachineInputs 必须优先用输出物品已有的 BLOCK_ENTITY_DATA 作为 base");
		assertTrue(source.contains("merged = nbts.get(0);"),
				"输出物品无数据（如玩家用空机器合成）时必须回退到首个输入 NBT，否则数据全丢");
		// 两个合并子步骤必须与 base 来源无关
		assertFalse(source.contains("for (int i = 1; i < inputs.size(); i++) {"),
				"不得再假设「首输入已在 base 中」：base 换成 MEK 结果后该假设不成立");
		assertTrue(source.contains("ListTag mergedBeeSlots = new ListTag();"),
				"蜜蜂槽必须从零重建");
		assertTrue(source.contains("CompoundTag mergedCounts = new CompoundTag();"),
				"PB 升级计数必须从零求和");
	}

	// ===== 构造辅助 =====

	private static CompoundTag countsTag(String typeId, int value) {
		CompoundTag counts = new CompoundTag();
		counts.putInt(typeId, value);
		return counts;
	}

	/** 机器级 NBT：把 PB 升级计数放在子复合标签下（与真实存档结构一致）。 */
	private static CompoundTag machineTag(CompoundTag counts) {
		CompoundTag machine = new CompoundTag();
		// 附带一个「其他字段」，验证合并时不会丢失首输入的非合并字段
		machine.putInt("energy", 1234);
		machine.put(PB_UPGRADE_KEY, counts);
		return machine;
	}

	/** 蜜蜂槽 ListTag（不是外层容器）。 */
	private static ListTag beeSlots(String... types) {
		ListTag slots = new ListTag();
		for (String type : types) {
			CompoundTag slot = new CompoundTag();
			slot.putString("type", type);
			slot.putInt("slot_index", slots.size());
			CompoundTag entity = new CompoundTag();
			entity.putString("id", "productivebees:configurable_bee");
			entity.putString("type", type);
			slot.put("entity_data", entity);
			slots.add(slot);
		}
		return slots;
	}

	private static CompoundTag beeSlotsTag(String... types) {
		CompoundTag holder = new CompoundTag();
		holder.put(BEE_SLOTS_KEY, beeSlots(types));
		return holder;
	}
}
