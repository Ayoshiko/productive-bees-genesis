package com.ayoshiko.productivebeesgenesis.client.screen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.ModContainer;
public final class CustomConfigScreenFactory {
 public Screen createScreen(ModContainer container, Screen parent) { return new ForgeConfigEditor(parent); }
}
