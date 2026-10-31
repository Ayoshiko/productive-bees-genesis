package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
	 * 客户端 → 服务端：切换指定方块实体的 per-tile NBT 忽略开关
	 * <br/>
	 * 携带 {@link BlockPos}，服务端校验玩家身份、8 格交互距离、
	 * 方块实体是否为 {@link com.ayoshiko.productivebeesgenesis.mek.ae2.IAe2InputHost} 后调用
	 * {@code productivebeesgenesis$toggleAeInputNbtIgnore()} 执行切换。
	 * <p>
	 * 设计原因：NBT 忽略开关决定拉取时是否区分蜜脾的 NBT 数据（如附魔、自定义标签），
	 * 服务端执行切换并持久化，避免客户端直接修改造成的状态不一致。
	 * IAe2InputHost 由 Mixin 运行时注入到 ME/EME 工厂类，运行时 instanceof 检查有效。
	 *
	 * @param pos 方块坐标
	 */
public record ToggleAeInputNbtIgnorePayload(
		BlockPos pos
)  {

    public static ToggleAeInputNbtIgnorePayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new ToggleAeInputNbtIgnorePayload(buf.readBlockPos());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
    }
}
