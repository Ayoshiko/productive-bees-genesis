package com.ayoshiko.productivebeesgenesis.apiculture.terminal;

import com.ayoshiko.productivebeesgenesis.apiculture.capacity.MemberCapabilitySnapshot.Origin;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.MemberClaim;
import com.ayoshiko.productivebeesgenesis.apiculture.ownership.OwnedMachineRecord;
import com.ayoshiko.productivebeesgenesis.apiculture.storage.*;
import java.util.List;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import static com.ayoshiko.productivebeesgenesis.apiculture.terminal.NetworkSelectionSession.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalFilterTest {
	private static final TerminalFilter.Names NAMES = new TerminalFilter.Names() {
		@Override public String text(String kind, String id) {
			return switch (kind) {
				case "item" -> id.equals("minecraft:iron_ingot") ? "iron ingot\n铁锭" : id.equals("minecraft:poppy") ? "poppy\n虞美人" : "";
				case "fluid" -> "liquid honey\n液态蜂蜜";
				case "bee" -> id.equals("productivebees:iron") ? "iron bee\n铁蜂" : "";
				case "machine" -> "apiary\n蜂箱";
				case "gene" -> id.endsWith("very_high") ? "very high\n极高" : "";
				default -> "";
			};
		}
		@Override public boolean tagged(ProductKey key, String tag) { return tag.equals("c:ingots") && key.kind() == ProductKey.Kind.ITEM; }
	};
	@Test void namesPhrasesNegationTagsAndKindsComposeWithoutChangingKeys() {
		var tag = new CompoundTag(); tag.putString("minecraft:custom_name", "\"Special iron bundle\"");
		var key = new ProductKey(ProductKey.Kind.ITEM, ResourceLocation.parse("minecraft:iron_ingot"), tag);
		var row = new ProductRow(key, ProductAmount.of(42), ProductAmount.of(40));
		for (var query : List.of("铁锭", "NAME:\"iron ingot\" @minecraft #c:ingots", "-name:\"gold ingot\" iron",
				"name:\"special iron bundle\"", "id:minecraft:iron kind:item", "\"iron ingot\"", "name:\"iron ingot"))
			assertTrue(new TerminalFilter(query).matches(row, NAMES), query);
		for (var query : List.of("kind:fluid", "-#c:ingots", "iron -铁锭", "id:gold", "bee:iron"))
			assertFalse(new TerminalFilter(query).matches(row, NAMES), query);
		assertEquals(42, row.owned().longSaturated());
		var fluid = new ProductRow(new ProductKey(ProductKey.Kind.FLUID, ResourceLocation.parse("test:honey"), new CompoundTag()),
				ProductAmount.of(1), ProductAmount.of(1));
		assertTrue(new TerminalFilter("kind:fluid 蜂蜜 @test").matches(fluid, NAMES));
	}
	@Test void beeGeneAndFlowerMustBelongToTheSameSlot() {
		var iron = bee(0, "productivebees:iron", "normal", "minecraft:iron_block", false);
		var gold = bee(1, "productivebees:gold", "very_high", "minecraft:poppy", true);
		var member = member(List.of(iron, gold));
		assertTrue(new TerminalFilter("蜂箱 bee:铁蜂 productivity:normal pos:1,64,2").matches(member, NAMES));
		assertFalse(new TerminalFilter("bee:iron productivity:极高").matches(member, NAMES));
		assertFalse(new TerminalFilter("bee:iron feed:虞美人").matches(member, NAMES));
		assertTrue(new TerminalFilter("bee:gold gene:\"very high\" state:feeding_disabled").matches(member, NAMES));
		assertTrue(new TerminalFilter("bee:iron -gene:very_high dim:overworld").matches(member, NAMES));
		assertFalse(new TerminalFilter("bee:gold state:feeding_active").matches(member, NAMES));
		assertTrue(new TerminalFilter("state:empty").matches(member(List.of(new BeeRow(2, null, "", 0, 0, false))), NAMES));
	}
	@Test void boundedInputAndDynamicFieldsAreExplicit() {
		assertThrows(IllegalArgumentException.class, () -> new TerminalFilter("x".repeat(65)));
		assertThrows(IllegalArgumentException.class, () -> new TerminalFilter("a\nb"));
		assertThrows(IllegalArgumentException.class, () -> new TerminalFilter(null));
		assertTrue(new TerminalFilter("-state:pending").dynamic());
		assertTrue(new TerminalFilter("feed:iron").dynamic());
		assertTrue(new TerminalFilter("#c:ingots").dynamic());
		assertFalse(new TerminalFilter("gene:normal").dynamic());
		assertTrue(new TerminalFilter(" - \" \" ").matches(member(List.of()), NAMES));
	}
	private static BeeRow bee(int slot, String type, String productivity, String flower, boolean disabled) {
		return new BeeRow(slot, UUID.randomUUID(), type, 0, 20, false, flower, 1, disabled,
				new TerminalBeeGenes(List.of(productivity, "normal", "passive", "diurnal", "none")));
	}
	private static MemberRow member(List<BeeRow> bees) {
		var claim = new MemberClaim(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				new Origin("minecraft:overworld", 1, 64, 2), "productivebeesgenesis:mek_apiary");
		return new MemberRow(claim, OwnedMachineRecord.Phase.OWNED, null, 0, bees);
	}
}
