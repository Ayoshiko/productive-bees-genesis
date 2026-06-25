package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;

import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

/**
 * Cosmic 渲染着色器管理
 * <br/>
 * 注册 cosmic 物品与护甲两套着色器，缓存 uniform 句柄；
 * 在 TextureAtlasStitchedEvent 中采集 misc/cosmic/cosmic_0..9 的 UV 坐标。
 */
public class CosmicShaders {

	public static final float[] COSMIC_UVS = new float[40];
	public static volatile TextureAtlasSprite[] COSMIC_SPRITES = new TextureAtlasSprite[10];

	/** 着色器实例，由 RegisterShadersEvent 回调（资源加载线程）写入，渲染线程读取，必须 volatile 保证可见性 */
	public static volatile ShaderInstance COSMIC_SHADER;
	public static volatile ShaderInstance COSMIC_ARMOR_SHADER;
	public static volatile ShaderInstance HELL_SHADER;

	/** uniform 句柄，随 shader 一起初始化，使用 volatile 保证跨线程可见性 */
	public static volatile AbstractUniform cosmicTime;
	public static volatile AbstractUniform cosmicYaw;
	public static volatile AbstractUniform cosmicPitch;
	public static volatile AbstractUniform cosmicExternalScale;
	public static volatile AbstractUniform cosmicOpacity;
	public static volatile AbstractUniform cosmicUVs;

	public static volatile AbstractUniform cosmicArmorTime;
	public static volatile AbstractUniform cosmicArmorYaw;
	public static volatile AbstractUniform cosmicArmorPitch;
	public static volatile AbstractUniform cosmicArmorExternalScale;
	public static volatile AbstractUniform cosmicArmorOpacity;
	public static volatile AbstractUniform cosmicArmorUVs;

	public static volatile AbstractUniform hellTime;
	public static volatile AbstractUniform hellYaw;
	public static volatile AbstractUniform hellPitch;
	public static volatile AbstractUniform hellExternalScale;
	public static volatile AbstractUniform hellOpacity;
	public static volatile AbstractUniform hellUVs;

	/** GUI/库存渲染标志，由 ScreenEvent（事件线程）设置，渲染线程读取，使用 volatile 保证可见性 */
	public static volatile boolean cosmicInventoryRender;

	@SubscribeEvent
	public static void onRegisterShaders(RegisterShadersEvent event) {
		try {
			event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "cosmic"), DefaultVertexFormat.BLOCK), shader -> {
				COSMIC_SHADER = shader;
				cosmicTime = COSMIC_SHADER.safeGetUniform("time");
				cosmicYaw = COSMIC_SHADER.safeGetUniform("yaw");
				cosmicPitch = COSMIC_SHADER.safeGetUniform("pitch");
				cosmicExternalScale = COSMIC_SHADER.safeGetUniform("externalScale");
				cosmicOpacity = COSMIC_SHADER.safeGetUniform("opacity");
				cosmicUVs = COSMIC_SHADER.safeGetUniform("cosmicuvs");
				COSMIC_SHADER.apply();
			});
			event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "cosmic"), DefaultVertexFormat.NEW_ENTITY), shader -> {
				COSMIC_ARMOR_SHADER = shader;
				cosmicArmorTime = COSMIC_ARMOR_SHADER.safeGetUniform("time");
				cosmicArmorYaw = COSMIC_ARMOR_SHADER.safeGetUniform("yaw");
				cosmicArmorPitch = COSMIC_ARMOR_SHADER.safeGetUniform("pitch");
				cosmicArmorExternalScale = COSMIC_ARMOR_SHADER.safeGetUniform("externalScale");
				cosmicArmorOpacity = COSMIC_ARMOR_SHADER.safeGetUniform("opacity");
				cosmicArmorUVs = COSMIC_ARMOR_SHADER.safeGetUniform("cosmicuvs");
				COSMIC_ARMOR_SHADER.apply();
			});
			event.registerShader(new ShaderInstance(event.getResourceProvider(), ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "hell"), DefaultVertexFormat.BLOCK), shader -> {
				HELL_SHADER = shader;
				hellTime = HELL_SHADER.safeGetUniform("time");
				hellYaw = HELL_SHADER.safeGetUniform("yaw");
				hellPitch = HELL_SHADER.safeGetUniform("pitch");
				hellExternalScale = HELL_SHADER.safeGetUniform("externalScale");
				hellOpacity = HELL_SHADER.safeGetUniform("opacity");
				hellUVs = HELL_SHADER.safeGetUniform("cosmicuvs");
				HELL_SHADER.apply();
			});
		} catch (Exception e) {
			throw new RuntimeException("Failed to register cosmic shaders", e);
		}
	}

	@SubscribeEvent
	public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
		if (event.getAtlas().location().equals(InventoryMenu.BLOCK_ATLAS)) {
			for (int i = 0; i < COSMIC_SPRITES.length; i++) {
				COSMIC_SPRITES[i] = event.getAtlas().getSprite(ResourceLocation.fromNamespaceAndPath("productivebeesgenesis", "misc/cosmic/cosmic_" + i));
				COSMIC_UVS[i * 4 + 0] = COSMIC_SPRITES[i].getU0();
				COSMIC_UVS[i * 4 + 1] = COSMIC_SPRITES[i].getV0();
				COSMIC_UVS[i * 4 + 2] = COSMIC_SPRITES[i].getU1();
				COSMIC_UVS[i * 4 + 3] = COSMIC_SPRITES[i].getV1();
			}
		}
	}
}
