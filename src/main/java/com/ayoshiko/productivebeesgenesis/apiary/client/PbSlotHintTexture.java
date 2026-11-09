package com.ayoshiko.productivebeesgenesis.apiary.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.SimpleTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;

/** 空槽图标引用已安装 PB 的资源；灰度副本仅在客户端内存中保留。 */
final class PbSlotHintTexture extends SimpleTexture {
    private PbSlotHintTexture(ResourceLocation source) {
        super(source);
    }

    static void register(ResourceLocation id, ResourceLocation source) {
        var textures = Minecraft.getInstance().getTextureManager();
        if (!(textures.getTexture(id, null) instanceof PbSlotHintTexture)) {
            textures.register(id, new PbSlotHintTexture(source));
        }
    }

    /** SimpleTexture 沿资源包重载重新读取原图，并负责释放已上传的像素。 */
    @Override
    protected TextureImage getTextureImage(ResourceManager resources) {
        TextureImage loaded = super.getTextureImage(resources);
        try {
            var pixels = loaded.getImage();
            for (int y = 0; y < pixels.getHeight(); y++) {
                for (int x = 0; x < pixels.getWidth(); x++) {
                    int color = pixels.getPixelRGBA(x, y);
                    int red = color & 255;
                    int green = color >>> 8 & 255;
                    int blue = color >>> 16 & 255;
                    int gray = (red * 299 + green * 587 + blue * 114 + 500) / 1000;
                    pixels.setPixelRGBA(x, y, color & 0xFF000000 | gray << 16 | gray << 8 | gray);
                }
            }
            return loaded;
        } catch (IOException failure) {
            loaded.close();
            return new TextureImage(failure);
        }
    }
}
