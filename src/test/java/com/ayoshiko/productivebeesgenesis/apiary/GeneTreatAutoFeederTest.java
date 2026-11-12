package com.ayoshiko.productivebeesgenesis.apiary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cy.jdkdigital.productivebees.util.GeneAttribute;
import cy.jdkdigital.productivebees.util.GeneValue;
import org.junit.jupiter.api.Test;

/** 基因小食自动喂食使用的属性优劣排序。 */
class GeneTreatAutoFeederTest {

	@Test
	void temperRanksLowerValuesAsBetter() {
		// PB 的 temper 序号越大越凶，passive(0) 才是玩家想要的；期望度必须翻转
		int passive = GeneAttributeRanking.desirability(
				GeneAttribute.TEMPER, GeneValue.TEMPER_PASSIVE);
		int hostile = GeneAttributeRanking.desirability(
				GeneAttribute.TEMPER, GeneValue.TEMPER_HOSTILE);
		assertTrue(passive > hostile,
				"性格越温顺期望度越高，否则自动喂食会把蜜蜂越喂越凶");
		assertEquals(0, GeneAttributeRanking.desirability(
				GeneAttribute.TEMPER, GeneValue.TEMPER_HOSTILE));
	}

	@Test
	void normalAttributesKeepAscendingOrder() {
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_VERY_HIGH)
				> GeneAttributeRanking.desirability(
						GeneAttribute.PRODUCTIVITY, GeneValue.PRODUCTIVITY_NORMAL));
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.WEATHER_TOLERANCE, GeneValue.WEATHER_TOLERANCE_ANY)
				> GeneAttributeRanking.desirability(
						GeneAttribute.WEATHER_TOLERANCE, GeneValue.WEATHER_TOLERANCE_NONE));
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.BEHAVIOR, GeneValue.BEHAVIOR_METATURNAL)
				> GeneAttributeRanking.desirability(
						GeneAttribute.BEHAVIOR, GeneValue.BEHAVIOR_DIURNAL));
		assertTrue(GeneAttributeRanking.desirability(
						GeneAttribute.ENDURANCE, GeneValue.ENDURANCE_STRONG)
				> GeneAttributeRanking.desirability(
						GeneAttribute.ENDURANCE, GeneValue.ENDURANCE_WEAK));
	}

	@Test
	void nullAndEmptyValuesRankAsWorst() {
		assertEquals(0, GeneAttributeRanking.desirability(GeneAttribute.PRODUCTIVITY, null));
		assertEquals(0, GeneAttributeRanking.desirability(
				GeneAttribute.PRODUCTIVITY, GeneValue.EMPTY));
	}

	@Test
	void typeAttributeIsNotRankable() {
		assertFalse(GeneAttributeRanking.isRankable(GeneAttribute.TYPE),
				"TYPE 是蜂种不是属性值，参与排序会触发 GeneSampleProfile 抛异常");
		assertTrue(GeneAttributeRanking.isRankable(GeneAttribute.PRODUCTIVITY));
	}

}
