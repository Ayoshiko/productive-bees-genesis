package com.ayoshiko.productivebeesgenesis.client;

import java.util.concurrent.ThreadLocalRandom;

import com.ayoshiko.productivebeesgenesis.MyriadCreationsEventHandler;
import com.ayoshiko.productivebeesgenesis.config.ModConfig;

import cy.jdkdigital.productivebees.common.entity.bee.ConfigurableBee;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.particles.DustParticleOptions;
import org.joml.Vector3f;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLivingEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/**
 * 万象创世蜜蜂客户端事件处理器
 * <p>
 * 负责：
 * <ol>
 *   <li>彩虹粒子特效</li>
 *   <li>渲染状态清理</li>
 * </ol>
 * <p>
 * 颜色控制：由 {@link com.ayoshiko.productivebeesgenesis.mixin.ConfigurableBeeColorMixin}
 * 直接注入 ConfigurableBee.getColor()，使用自定义 8秒 彩虹循环，
 * 不再操作 RenderSystem 全局状态，避免影响其他实体。
 */
@EventBusSubscriber(modid = "productivebeesgenesis", value = Dist.CLIENT)
public final class MyriadCreationsClientEventHandler {

    /** 彩虹颜色变化周期（毫秒）- 8秒完成一次完整循环 */
    private static final long RAINBOW_CYCLE_MS = 8000;

    /** HSV固定饱和度 - 柔和不刺眼 */
    private static final float HSV_SATURATION = 0.55F;
    /** HSV固定明度 - 明亮通透 */
    private static final float HSV_VALUE = 0.9F;

    /**
     * 获取当前时间对应的彩虹颜色（HSV连续色相环，无跳跃）
     * <p>
     * 原理：使用HSV色彩模型，色相(Hue)随时间在0°-360°连续变化，
     * 固定饱和度和明度，实现丝滑的彩虹渐变，消除离散颜色插值造成的突变。
     */
    public static float[] getRainbowColor(long time) {
        // 时间映射到色相 0~360°
        double hue = ((time % RAINBOW_CYCLE_MS) / (double) RAINBOW_CYCLE_MS) * 360.0;
        return hsvToRgb((float) hue, HSV_SATURATION, HSV_VALUE);
    }

    /** HSV → RGB 转换 */
    private static float[] hsvToRgb(float h, float s, float v) {
        float c = v * s;
        float x = c * (1 - Math.abs(((h / 60) % 2) - 1));
        float m = v - c;

        float r, g, b;
        if (h < 60)       { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else              { r = c; g = 0; b = x; }

        return new float[]{r + m, g + m, b + m};
    }

    /** 粒子生成间隔（tick），约每秒5次而非每秒20次 */
    private static final int PARTICLE_TICK_INTERVAL = 4;
    private static int particleTickCounter = 0;

    /**
     * 客户端LevelTick事件 - 生成彩虹粒子
     * <p>
     * 先过滤客户端世界，再跳帧减少密度。
     * 计数器放在ClientLevel检查之后，避免被服务端维度事件干扰。
     */
    @SubscribeEvent
    public static void onClientTick(LevelTickEvent.Post event) {
        if (!ModConfig.CLIENT.particleEffectEnabled.get()) return;

        // 只处理客户端世界
        Level level = event.getLevel();
        if (!level.isClientSide()) return;

        // 跳帧：每 N tick 才生成粒子（计数器放在ClientLevel检查后）
        particleTickCounter++;
        if (particleTickCounter % PARTICLE_TICK_INTERVAL != 0) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        long time = System.currentTimeMillis();
        float[] rainbowColor = getRainbowColor(time);
        int count = ModConfig.CLIENT.particleCount.get();

        // 遍历客户端世界所有实体
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof ConfigurableBee bee)) continue;
            if (!MyriadCreationsEventHandler.MYRIADCREATIONS_TYPE.equals(bee.getBeeType())) continue;

            Vec3 pos = entity.position();

            for (int i = 0; i < count; i++) {
                double offsetX = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.5;
                double offsetY = ThreadLocalRandom.current().nextDouble() * 1.2 + 0.2;
                double offsetZ = (ThreadLocalRandom.current().nextDouble() - 0.5) * 1.5;

                float[] color;
                if (ThreadLocalRandom.current().nextBoolean()) {
                    color = rainbowColor;
                } else {
                    color = hsvToRgb(ThreadLocalRandom.current().nextFloat() * 360F, HSV_SATURATION, HSV_VALUE);
                }

                mc.level.addParticle(
                        new DustParticleOptions(new Vector3f(color[0], color[1], color[2]), 0.8F),
                        pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ,
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.1,
                        ThreadLocalRandom.current().nextDouble() * 0.1 + 0.05,
                        (ThreadLocalRandom.current().nextDouble() - 0.5) * 0.1
                );

                // 8%概率生成发光粒子
                if (ThreadLocalRandom.current().nextInt(12) == 0) {
                    float[] glowColor = hsvToRgb(ThreadLocalRandom.current().nextFloat() * 360F, HSV_SATURATION, HSV_VALUE);
                    mc.level.addParticle(
                            new DustParticleOptions(new Vector3f(glowColor[0], glowColor[1], glowColor[2]), 1.2F),
                            pos.x + offsetX, pos.y + offsetY + 0.3, pos.z + offsetZ,
                            0, 0.1, 0
                    );
                }
            }
        }
    }

    /**
     * 实体渲染后事件 - 发光轮廓
     * <p>
     * 在蜜蜂主体渲染完成后，额外渲染一层放大版的半透明轮廓，
     * 使用 entityTranslucent 渲染类型配合彩虹颜色产生动态光晕。
     */
    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<LivingEntity, ?> event) {
        LivingEntity entity = event.getEntity();

        if (!(entity instanceof ConfigurableBee bee)) return;
        if (!MyriadCreationsEventHandler.MYRIADCREATIONS_TYPE.equals(bee.getBeeType())) return;
        if (!ModConfig.CLIENT.glowEnabled.get()) return;

        // 获取当前彩虹色
        float[] rainbow = getRainbowColor(System.currentTimeMillis());

        var poseStack = event.getPoseStack();
        var multiBufferSource = event.getMultiBufferSource();
        var renderer = event.getRenderer();
        var model = renderer.getModel();

        // 准备模型动画（walkAnimation等）
        float partialTick = event.getPartialTick();
        float limbSwing = entity.walkAnimation.position();
        float limbSwingAmount = entity.walkAnimation.speed();
        float ageInTicks = entity.tickCount + partialTick;
        float netHeadYaw = entity.getYRot();
        float headPitch = entity.getXRot();
        model.prepareMobModel(entity, limbSwing, limbSwingAmount, partialTick);
        model.setupAnim(entity, limbSwing, limbSwingAmount, ageInTicks, netHeadYaw, headPitch);

        poseStack.pushPose();

        // 放大1.08倍作为发光轮廓
        poseStack.scale(1.08F, 1.08F, 1.08F);

        // 使用半透明渲染类型渲染模型本身（不触发完整渲染管线，避免无限递归）
        var renderType = RenderType.entityTranslucent(renderer.getTextureLocation(entity));
        var vertexConsumer = multiBufferSource.getBuffer(renderType);
        int overlay = OverlayTexture.pack(OverlayTexture.u(0.0F), OverlayTexture.v(false));

        // 使用 RenderSystem.setShaderColor 控制发光颜色和透明度
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(rainbow[0], rainbow[1], rainbow[2], 0.35F);
        model.renderToBuffer(poseStack, vertexConsumer, event.getPackedLight(), overlay);
        com.mojang.blaze3d.systems.RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        poseStack.popPose();
    }

    /**
     * 实体加入世界时生成粒子爆发
     */
    @SubscribeEvent
    public static void onEntityJoinLevel(EntityJoinLevelEvent event) {
        Entity entity = event.getEntity();
        if (!(entity instanceof ConfigurableBee bee)) return;
        if (!MyriadCreationsEventHandler.MYRIADCREATIONS_TYPE.equals(bee.getBeeType())) return;

        if (event.getLevel().isClientSide()) {
            Vec3 pos = entity.position();

            for (int i = 0; i < 20; i++) {
                float[] color = hsvToRgb((i / 20F) * 360F, HSV_SATURATION, HSV_VALUE);
                double speed = ThreadLocalRandom.current().nextDouble() * 0.5;
                double angle = ThreadLocalRandom.current().nextDouble() * Math.PI * 2;

                event.getLevel().addParticle(
                        new DustParticleOptions(new Vector3f(color[0], color[1], color[2]), 0.8F),
                        pos.x, pos.y + 0.5, pos.z,
                        Math.cos(angle) * speed,
                        ThreadLocalRandom.current().nextDouble() * 0.5 + 0.2,
                        Math.sin(angle) * speed
                );
            }
        }
    }
}