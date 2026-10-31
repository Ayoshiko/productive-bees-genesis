package com.ayoshiko.productivebeesgenesis.mixin.accessor;

import mekanism.api.text.ILangEntry;
import mekanism.common.content.blocktype.BlockType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Installs a Genesis description when old optional factory builders only accept MekanismLang. */
@Mixin(value = BlockType.class, remap = false)
public interface BlockTypeDescriptionAccessor {
    @Mutable @Accessor("description") void productivebeesgenesis$setDescription(ILangEntry description);
}
