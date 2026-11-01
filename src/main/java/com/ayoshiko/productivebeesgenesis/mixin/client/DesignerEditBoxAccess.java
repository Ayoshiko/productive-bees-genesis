package com.ayoshiko.productivebeesgenesis.mixin.client;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(EditBox.class)
public interface DesignerEditBoxAccess {
 @Accessor("shiftPressed") void genesis$selecting(boolean selecting);
}
