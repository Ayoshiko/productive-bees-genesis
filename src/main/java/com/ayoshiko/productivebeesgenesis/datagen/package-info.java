/**
 * 数据生成包
 * <br/>
 * 包含模组的数据生成器，用于自动生成：
 * <ul>
 *   <li>ModRecipes — 配方生成</li>
 *   <li>ModLootTables — 战利品表生成</li>
 *   <li>ModBlockTagsProvider — 方块标签生成（镐/锄挖掘工具）</li>
 * </ul>
 * <p>
 * 语言文件由 src/main/resources 下手写 lang 文件作为单一真相源，
 * 不再通过 LanguageProvider 生成，避免 generated lang 与主 lang 键重叠触发 DuplicatesStrategy.EXCLUDE。
 */
@ParametersAreNonnullByDefault
@FieldsAreNonnullByDefault
@MethodsReturnNonnullByDefault
package com.ayoshiko.productivebeesgenesis.datagen;

import net.minecraft.FieldsAreNonnullByDefault;
import net.minecraft.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;
