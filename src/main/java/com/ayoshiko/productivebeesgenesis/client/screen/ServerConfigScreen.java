package com.ayoshiko.productivebeesgenesis.client.screen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraft.network.chat.Component;
public final class ServerConfigScreen extends ForgeConfigEditor {
 public ServerConfigScreen(Screen parent, ModConfig config) { super(parent,(ForgeConfigSpec)config.getSpec(),((ForgeConfigSpec)config.getSpec()).getValues(),Component.translatable("productivebeesgenesis.config.gameplay")); }
}
