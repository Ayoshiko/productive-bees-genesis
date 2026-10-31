package com.ayoshiko.productivebeesgenesis.network;

import com.ayoshiko.productivebeesgenesis.ProductiveBeesGenesis;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 客户端 → 服务端：批量设置机械蜂箱全部喂食槽的禁用状态（Shift + 点击「禁」按钮）
 * <br/>
 * 与单格切换 {@link ToggleFeederSlotDisabledPayload} 分开成独立包而不是复用
 * {@code slotIndex = -1} 哨兵值：哨兵会让服务端的槽位索引边界校验失去意义，
 * 独立包可以保持"索引必须落在合法范围内"这条硬约束不被削弱。
 * <p>
 * 传绝对目标状态（{@code disabled}）而非"翻转"：批量翻转在多人同时操作时会互相打架，
 * 绝对值天然幂等，客户端按当前同步状态决定方向即可。空格子由服务端跳过。
 *
 * @param pos      蜂箱方块坐标
 * @param disabled true = 全部停用，false = 全部恢复
 */
public record SetAllFeederSlotsDisabledPayload(
		BlockPos pos,
		boolean disabled
)  {

	/** 数据包类型标识 */

	/** 流编解码器 — 编解码 BlockPos + boolean */

    public static SetAllFeederSlotsDisabledPayload decode(net.minecraft.network.FriendlyByteBuf buf) {
        return new SetAllFeederSlotsDisabledPayload(buf.readBlockPos(), buf.readBoolean());
    }
    public void encode(net.minecraft.network.FriendlyByteBuf buf) {
                buf.writeBlockPos(pos);
        buf.writeBoolean(disabled);
    }
}
