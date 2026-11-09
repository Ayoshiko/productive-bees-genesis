package com.ayoshiko.productivebeesgenesis.client.render.cosmic;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import com.mojang.blaze3d.shaders.AbstractUniform;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import net.neoforged.neoforge.client.event.TextureAtlasStitchedEvent;

import java.util.concurrent.atomic.AtomicReference;

/**
	 * Cosmic 渲染着色器管理
	 * <br/>
	 * 注册 cosmic 物品与护甲两套着色器，缓存 uniform 句柄；
	 * 在 TextureAtlasStitchedEvent 中采集 misc/cosmic/cosmic_0..9 的 UV 坐标。
	 * <p>
	 * 线程安全：UV/Sprite 数组通过 {@link AtomicReference} 整体替换保证可见性。
	 * 资源加载线程（TextureAtlasStitchedEvent）写入，渲染线程读取。原数组元素修改无 happens-before 保证，
	 * 可能导致渲染线程读到部分更新的 UV 坐标。改为构建完整快照后原子替换引用，渲染线程要么看到旧快照，
	 * 要么看到新快照，不会看到中间状态。
	 */
public class CosmicShaders {

	/** UV 坐标快照（不可变，含 10 个 sprite × 4 个 float = 40 个元素） */
	private record CosmicUvSnapshot(float[] uvs, TextureAtlasSprite[] sprites) {
		static final CosmicUvSnapshot EMPTY = new CosmicUvSnapshot(new float[40], new TextureAtlasSprite[10]);
	}

	/** 当前 UV 快照 — AtomicReference 保证原子替换，渲染线程读到的要么是旧快照要么是新快照 */
	private static final AtomicReference<CosmicUvSnapshot> cosmicUvSnapshot =
			new AtomicReference<>(CosmicUvSnapshot.EMPTY);

	/** 兼容旧调用：返回当前快照的 UV 数组引用（快照不可变，引用安全） */
	public static float[] getCosmicUvs() {
		return cosmicUvSnapshot.get().uvs();
	}

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
		clearShaders();
		CosmicRenderQueue.clear();
		// 两个 shader 独立 try-catch：单个 shader 注册失败只记录 warn，不影响其他 shader 注册
		// 原共用 try-catch 会导致首个失败时后续 shader 全部跳过（如 cosmic 失败则 armor/hell 均不注册）
		//
		// 顶点格式说明（v2.0.9 修复）：cosmic/hell 的 RenderType 均使用 NEW_ENTITY 顶点格式，
		// 此前 cosmic 以 BLOCK 格式注册会把 Position/Normal/Color 属性布局错位（Color/Normal 互换），
		// 且同一路径 "cosmic" 以两种格式注册两次会导致 GameRenderer.shaders 同名覆盖、
		// 旧实例永不 close（每次资源重载泄漏一个 GL 程序）。
		// 修复：cosmic 仅以 NEW_ENTITY 注册一次，COSMIC_SHADER 与 COSMIC_ARMOR_SHADER 共用同一实例
		// （两者的 shader 文件与 uniform 完全相同，仅 RenderType 的纹理/混合状态不同）。
		try {
			event.registerShader(new ShaderInstance(event.getResourceProvider(),
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "cosmic"),
				DefaultVertexFormat.NEW_ENTITY),
				shader -> {
				cosmicTime = shader.safeGetUniform("time");
				cosmicYaw = shader.safeGetUniform("yaw");
				cosmicPitch = shader.safeGetUniform("pitch");
				cosmicExternalScale = shader.safeGetUniform("externalScale");
				cosmicOpacity = shader.safeGetUniform("opacity");
				cosmicUVs = shader.safeGetUniform("cosmicuvs");
				cosmicArmorTime = cosmicTime;
				cosmicArmorYaw = cosmicYaw;
				cosmicArmorPitch = cosmicPitch;
				cosmicArmorExternalScale = cosmicExternalScale;
				cosmicArmorOpacity = cosmicOpacity;
				cosmicArmorUVs = cosmicUVs;
				COSMIC_SHADER = shader;
				COSMIC_ARMOR_SHADER = shader;
			});
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("注册 cosmic 着色器失败", e);
		}
		try {
			event.registerShader(new ShaderInstance(event.getResourceProvider(),
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "hell"),
				DefaultVertexFormat.NEW_ENTITY),
				shader -> {
				hellTime = shader.safeGetUniform("time");
				hellYaw = shader.safeGetUniform("yaw");
				hellPitch = shader.safeGetUniform("pitch");
				hellExternalScale = shader.safeGetUniform("externalScale");
				hellOpacity = shader.safeGetUniform("opacity");
				hellUVs = shader.safeGetUniform("cosmicuvs");
				HELL_SHADER = shader;
			});
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("注册 hell 着色器失败", e);
		}
	}

	/** 引擎拥有并关闭实例；这里只撤销借用引用，防止失败重载继续使用旧 GL 程序。 */
	static void clearShaders() {
		COSMIC_SHADER = null;
		COSMIC_ARMOR_SHADER = null;
		HELL_SHADER = null;
		cosmicTime = null;
		cosmicYaw = null;
		cosmicPitch = null;
		cosmicExternalScale = null;
		cosmicOpacity = null;
		cosmicUVs = null;
		cosmicArmorTime = null;
		cosmicArmorYaw = null;
		cosmicArmorPitch = null;
		cosmicArmorExternalScale = null;
		cosmicArmorOpacity = null;
		cosmicArmorUVs = null;
		hellTime = null;
		hellYaw = null;
		hellPitch = null;
		hellExternalScale = null;
		hellOpacity = null;
		hellUVs = null;
	}

	public static boolean isCosmicAtlasReady() {
		return cosmicUvSnapshot.get() != CosmicUvSnapshot.EMPTY;
	}

	@SubscribeEvent
	public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
		if (!event.getAtlas().location().equals(InventoryMenu.BLOCK_ATLAS)) {
			return;
		}
		float[] newUvs = new float[40];
		TextureAtlasSprite[] newSprites = new TextureAtlasSprite[10];
		for (int i = 0; i < newSprites.length; i++) {
			ResourceLocation id = ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID,
					"misc/cosmic/cosmic_" + i);
			TextureAtlasSprite sprite = event.getAtlas().getSprite(id);
			if (!sprite.contents().name().equals(id)) {
				// 缺图时撤销整个旧快照；基础模型保留，不让效果采样占位图或旧图集。
				cosmicUvSnapshot.set(CosmicUvSnapshot.EMPTY);
				ProductiveBeesGenesis.LOGGER.warn("缺少星空精灵 {}，本次重载停用星空层", id);
				return;
			}
			newSprites[i] = sprite;
			// 端点取首尾纹素中心，旋转／翻转得到 0 或 1 时仍留在本精灵内；单纹素两端重合。
			float halfU = 0.5F / sprite.contents().width();
			float halfV = 0.5F / sprite.contents().height();
			newUvs[i * 4 + 0] = sprite.getU(halfU);
			newUvs[i * 4 + 1] = sprite.getV(halfV);
			newUvs[i * 4 + 2] = sprite.getU(1.0F - halfU);
			newUvs[i * 4 + 3] = sprite.getV(1.0F - halfV);
		}
		cosmicUvSnapshot.set(new CosmicUvSnapshot(newUvs, newSprites));
	}
}
