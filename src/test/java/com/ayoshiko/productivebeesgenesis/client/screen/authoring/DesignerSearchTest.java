package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class DesignerSearchTest {
	@Test
	void chineseNamesSupportFullPinyinInitialsAndUmlautInput() {
		String name = DesignerSearch.indexed("productivebees:emerald 绿宝石蜜蜂");
		for (String query : List.of("绿宝石", "lvbaoshi", "lubaoshi", "lǜ bǎo shí", "lbsmf", "emerald")) {
			assertTrue(DesignerSearch.matches(query, "productivebees", name, List.of()), query);
		}
		assertFalse(DesignerSearch.matches("huangjin", "productivebees", name, List.of()));
	}

	@Test
	void nativeBeeNamesMatchSingleChineseCharactersAndPinyin() {
		String blue = DesignerSearch.indexed("productivebees:blue_banded_bee 蓝色条纹蜜蜂");
		for (String query : List.of("蓝", "蓝色", "蓝色条纹蜜蜂", "lan", "lanse", "blue_banded")) {
			assertTrue(DesignerSearch.matches(query, "productivebees", blue, List.of()), query);
		}
		String creeper = DesignerSearch.indexed("productivebees:creeper_bee 苦力怕蜜蜂");
		assertTrue(DesignerSearch.matches("苦力怕", "productivebees", creeper, List.of()));
		assertTrue(DesignerSearch.matches("kulipa", "productivebees", creeper, List.of()));
	}

	@Test
	void pinyinPreservesTermIntersectionNamespaceAndTagScopes() {
		String name = DesignerSearch.indexed("minecraft:copper_ingot 铜锭");
		assertTrue(DesignerSearch.matches("@minecraft tongding #ingots", "minecraft", name, List.of("c:ingots/copper")));
		assertTrue(DesignerSearch.matches("TD copper", "minecraft", name, List.of()));
		assertFalse(DesignerSearch.matches("@productivebees td", "minecraft", name, List.of()));
		assertFalse(DesignerSearch.matches("td iron", "minecraft", name, List.of()));
	}

	@Test
	void indexPreservesScopesAndContainsMatchingAcrossQueryChanges() {
		var index = new DesignerSearch.Index<String>(java.util.Comparator.naturalOrder());
		index.add("iron", "minecraft", DesignerSearch.indexed("minecraft:iron_ingot 铁锭"), List.of("c:ingots/iron"));
		index.add("copper", "minecraft", DesignerSearch.indexed("minecraft:copper_ingot 铜锭"), List.of("c:ingots/copper"));
		index.add("bee", "productivebees", DesignerSearch.indexed("productivebees:emerald 绿宝石蜜蜂"), List.of());
		index.finish();
		assertEquals(List.of("iron"), index.search("tie"));
		assertEquals(List.of("iron"), index.search("TIĚ #ingots @minecraft"));
		assertEquals(List.of("copper", "iron"), index.search("@mine #c:ingots"));
		assertEquals(List.of("bee"), index.search("lbsmf"));
		assertEquals(List.of(), index.search("@mine lbsmf"));
		assertEquals(List.of(), index.search("tie #c:ingots/copper"));
		assertEquals(List.of("copper", "iron"), index.search("ding"));
		assertEquals(List.of("iron"), index.search("ding tie"));
		assertEquals(List.of("bee", "copper", "iron"), index.search(""));
		assertEquals(List.of("iron"), index.search("铁"));
		assertEquals(List.of("bee", "copper", "iron"), index.search("t"));
		assertEquals(List.of("bee", "iron"), index.search("ti"));
		assertEquals(List.of("iron"), index.search("tie"));
		index.add("gold", "minecraft", DesignerSearch.indexed("minecraft:gold_ingot 金锭"), List.of("c:ingots/gold"));
		index.finish();
		assertEquals(List.of("copper", "gold", "iron"), index.search("#ingots"));
	}

	@Test
	void cachedSyllablesPreserveContextualHanReadings() {
		for (String name : List.of("重庆", "藏文", "沈阳", "秘鲁", "绿宝石蜜蜂", "铁锭")) {
			String expected = DesignerSearch.fold(com.ibm.icu.text.Transliterator.getInstance("Han-Latin").transliterate(name));
			assertTrue(DesignerSearch.indexed(name).contains(expected), name);
		}
	}
}
