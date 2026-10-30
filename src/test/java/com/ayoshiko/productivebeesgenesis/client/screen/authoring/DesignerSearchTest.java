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
	void pinyinPreservesTermIntersectionNamespaceAndTagScopes() {
		String name = DesignerSearch.indexed("minecraft:copper_ingot 铜锭");
		assertTrue(DesignerSearch.matches("@minecraft tongding #ingots", "minecraft", name, List.of("c:ingots/copper")));
		assertTrue(DesignerSearch.matches("TD copper", "minecraft", name, List.of()));
		assertFalse(DesignerSearch.matches("@productivebees td", "minecraft", name, List.of()));
		assertFalse(DesignerSearch.matches("td iron", "minecraft", name, List.of()));
	}
}
