package com.ayoshiko.productivebeesgenesis.apiary;

/** Comparison of Productive Bees integer attributes; passive temper is preferred. */
final class GeneAttributeRanking {
    private GeneAttributeRanking() {}

    static int desirability(GeneAttribute attribute, int value) {
        return attribute == GeneAttribute.TEMPER ? attribute.maxValue - value : value;
    }

    static boolean isRankable(GeneAttribute attribute) {
        return attribute != GeneAttribute.TYPE;
    }
}
