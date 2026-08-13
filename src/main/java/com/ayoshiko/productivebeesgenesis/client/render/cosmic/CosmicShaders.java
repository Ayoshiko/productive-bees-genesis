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
				COSMIC_SHADER = shader;
				COSMIC_ARMOR_SHADER = shader;
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
				shader.apply();
			});
		} catch (Exception e) {
			ProductiveBeesGenesis.LOGGER.warn("注册 cosmic 着色器失败", e);
		}
		try {
			event.registerShader(new ShaderInstance(event.getResourceProvider(),
				ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID, "hell"),
				DefaultVertexFormat.NEW_ENTITY),
				shader -> {
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
			ProductiveBeesGenesis.LOGGER.warn("注册 hell 着色器失败", e);
		}
	}

	@SubscribeEvent
	public static void onTextureAtlasStitched(TextureAtlasStitchedEvent event) {
		if (event.getAtlas().location().equals(InventoryMenu.BLOCK_ATLAS)) {
			// 构建完整快照后原子替换：避免渲染线程读到部分更新的数组元素
			float[] newUvs = new float[40];
			TextureAtlasSprite[] newSprites = new TextureAtlasSprite[10];
			for (int i = 0; i < newSprites.length; i++) {
				newSprites[i] = event.getAtlas().getSprite(ResourceLocation.fromNamespaceAndPath(ProductiveBeesGenesis.MOD_ID,
					"misc/cosmic/cosmic_" + i));
				newUvs[i * 4 + 0] = newSprites[i].getU0();
				newUvs[i * 4 + 1] = newSprites[i].getV0();
				newUvs[i * 4 + 2] = newSprites[i].getU1();
				newUvs[i * 4 + 3] = newSprites[i].getV1();
			}
			cosmicUvSnapshot.set(new CosmicUvSnapshot(newUvs, newSprites));
		}
	}
}
