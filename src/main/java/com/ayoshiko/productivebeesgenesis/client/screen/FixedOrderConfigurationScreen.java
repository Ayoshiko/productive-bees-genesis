package com.ayoshiko.productivebeesgenesis.client.screen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.common.ForgeConfigSpec;
public final class FixedOrderConfigurationScreen extends ForgeConfigEditor {
 public FixedOrderConfigurationScreen(Screen parent, ModConfig.Type type, ModConfig config, Component title) { super(parent,(ForgeConfigSpec)config.getSpec(),((ForgeConfigSpec)config.getSpec()).getValues(),title); }
}
