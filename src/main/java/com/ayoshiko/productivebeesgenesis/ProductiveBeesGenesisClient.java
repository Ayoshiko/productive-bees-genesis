package com.ayoshiko.productivebeesgenesis;

import com.ayoshiko.productivebeesgenesis.apiary.MekApiaryContainer;
import com.ayoshiko.productivebeesgenesis.apiary.client.GuiMekApiary;
import com.ayoshiko.productivebeesgenesis.apiary.client.GuiMekApiaryFactory;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.AbstractBakedModelCosmic;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.BakedModelHalo;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.CosmicRenderQueue;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.CosmicShaders;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.GeometryLoaderCosmic;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.GeometryLoaderHalo;
import com.ayoshiko.productivebeesgenesis.client.render.cosmic.GeometryLoaderHell;
import com.ayoshiko.productivebeesgenesis.client.screen.CustomConfigScreenFactory;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiMekCentrifuge;
import com.ayoshiko.productivebeesgenesis.client.screen.GuiMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.init.ModMenuTypes;
import com.ayoshiko.productivebeesgenesis.compat.emextras.TileEntityEMExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.compat.mekanism_extras.TileEntityExtraMekCentrifugeFactory;
import com.ayoshiko.productivebeesgenesis.mek.TileEntityMekCentrifuge;

import mekanism.common.inventory.container.tile.MekanismTileContainer;
import mekanism.common.tile.factory.TileEntityFactory;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 资源蜜蜂：创世模组客户端专用初始化
 * <br/>
 * 注册配置屏幕工厂和MEK离心机Screen映射。
 * Screen注册使用RegisterMenuScreensEvent，与Mekanism的注册方式一致。
 */
@Mod(value = ProductiveBeesGenesis.MOD_ID, dist = Dist.CLIENT)
public final class ProductiveBeesGenesisClient {

	public ProductiveBeesGenesisClient(IEventBus eventBus, ModContainer container) {
		// 注册自定义配置屏幕 — 替代 NeoForge 默认 ConfigurationScreen
		// 原因：默认 ConfigurationScreen 不支持空列表添加项，自定义屏幕提供完整的过滤列表编辑功能
		// 直接传递实例避免 registerExtensionPoint 重载歧义
		container.registerExtensionPoint(IConfigScreenFactory.class, new CustomConfigScreenFactory());
	}

	/**
	 * Screen注册事件处理器
	 * <br/>
	 * 使用@EventBusSubscriber注册到MOD事件总线，仅在客户端执行。
	 */
	@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
	public static final class ScreenRegistry {
		@SubscribeEvent
		@SuppressWarnings({"rawtypes", "unchecked"})
		public static void registerScreens(RegisterMenuScreensEvent event) {
			// 基础MEK离心机Screen
			event.register(ModMenuTypes.MEK_CENTRIFUGE.get(), GuiMekCentrifuge::new);

			// MEK通用机械蜂箱Screen
			// 使用 lambda 而非方法引用：GuiMekApiary 已泛型化，方法引用无法推断类型参数
			event.register(ModMenuTypes.MEK_APIARY.get(),
					(MekApiaryContainer menu, Inventory inv, Component title) -> new GuiMekApiary<>(menu, inv, title));

			// 工厂版MEK通用机械蜂箱Screen（4个等级共用，运行时根据 tile.getTier() 区分）
			event.register(ModMenuTypes.MEK_APIARY_FACTORY.get(), GuiMekApiaryFactory::new);

			// 工厂版MEK离心机Screen  需要类型转换
			event.register((MenuType) ModMenuTypes.MEK_CENTRIFUGE_FACTORY.get(),
					(MenuScreens.ScreenConstructor) (menu, inv, title) ->
							new GuiMekCentrifugeFactory(
									(MekanismTileContainer<TileEntityFactory<?>>) (MekanismTileContainer<?>) menu,
									inv, title));

			// ME扩展版离心机工厂Screen
			event.register(ModMenuTypes.EXTRA_MEK_CENTRIFUGE_FACTORY.get(), GuiExtraMekCentrifugeFactory::new);

			// EME扩展版离心机工厂Screen
			event.register(ModMenuTypes.EMEXTRA_MEK_CENTRIFUGE_FACTORY.get(), GuiEMExtraMekCentrifugeFactory::new);
		}
	}

	/**
	 * Cosmic 渲染系统注册
	 * <br/>
	 * 使用@EventBusSubscriber注册到MOD事件总线，仅在客户端执行。
	 * 负责注册：
	 * <ol>
	 *   <li>cosmic 几何加载器（ModelEvent.RegisterGeometryLoaders）</li>
	 *   <li>cosmic 着色器（RegisterShadersEvent）</li>
	 *   <li>cosmic 纹理 UV 采集（TextureAtlasStitchedEvent）</li>
	 * </ol>
	 */
	@EventBusSubscriber(modid = ProductiveBeesGenesis.MOD_ID, value = Dist.CLIENT)
	public static final class CosmicRenderRegistry {
		@SubscribeEvent
		public static void onRegisterGeometryLoaders(ModelEvent.RegisterGeometryLoaders event) {
			event.register(ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "cosmic"), new GeometryLoaderCosmic());
			event.register(ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "halo"), new GeometryLoaderHalo());
			event.register(ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "hell"), new GeometryLoaderHell());
		}

		@SubscribeEvent
		public static void onRegisterShaders(RegisterShadersEvent event) {
			CosmicShaders.onRegisterShaders(event);
		}

		@SubscribeEvent
		public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
			CosmicShaders.onTextureAtlasStitched(event);
			// 失效 halo 四边形缓存：图集重建后 UV 可能变化，旧缓存会导致 halo 渲染错位或采样错误纹理
			BakedModelHalo.invalidateCache();
			// 失效 cosmic 烘焙四边形缓存：图集重建后 atlasSprites 的 UV 变化，旧 bakedQuads 会采样错误纹理
			AbstractBakedModelCosmic.invalidateCache();
		}

		@SubscribeEvent
		public static void onRenderLevelAfterLevel(RenderLevelStageEvent event) {
			if (event.getStage() == RenderLevelStageEvent.Stage.AFTER_LEVEL) {
				// 强制重置 GUI 渲染标志：防止 ScreenEvent.Render.Pre 设为 true 后 Post 未触发（异常情况）导致标志位卡在 true。
				// AFTER_LEVEL 阶段一定处于世界渲染，不应使用 GUI 模式的固定视角参数。
				CosmicShaders.cosmicInventoryRender = false;
				CosmicRenderQueue.renderAll();
			}
		}

		/**
		 * GUI 屏幕渲染前事件
		 * <br/>
		 * 设置 cosmicInventoryRender 标志为 true，使 cosmic 渲染使用固定视角参数（scale=100）。
		 * 这确保 GUI 中物品的星空效果呈现静态星空而非动态视角流动。
		 */
		@SubscribeEvent
		public static void onScreenRenderPre(ScreenEvent.Render.Pre event) {
			CosmicShaders.cosmicInventoryRender = true;
		}

		/**
		 * GUI 屏幕渲染后事件
		 * <br/>
		 * 重置 cosmicInventoryRender 标志为 false，恢复世界模式的动态视角参数。
		 */
		@SubscribeEvent
		public static void onScreenRenderPost(ScreenEvent.Render.Post event) {
			CosmicShaders.cosmicInventoryRender = false;
		}
	}
}
