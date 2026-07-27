package com.ayoshiko.productivebeesgenesis.apiary;

import java.util.concurrent.ThreadLocalRandom;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;

import mekanism.api.Upgrade;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

/**
 * 蜂箱工作声音处理器 — 播放 PB 原版蜂箱的蜜蜂嗡嗡声
 * <br/>
 * Task 4：机器方块工作声音使用 PB 蜂箱的蜜蜂声音（SoundEvents.BEEHIVE_WORK）。
 * <p>
 * 参考 PB 原版 {@code AdvancedBeehiveBlockEntityAbstract.tick()} 的声音播放逻辑：
 * 蜂箱非空时按概率播放 BEEHIVE_WORK 声音。
 * <p>
 * 与 PB 原版的差异：
 * <ul>
 *   <li>概率从 0.5% 降至 0.3%，避免多台机械蜂箱同时工作时声音叠加吵闹</li>
 *   <li>仅在服务端 tick 且有蜜蜂工作时播放（由调用方判断 workingCount > 0）</li>
 * </ul>
 * <p>
 * 设计原则（SRP）：独立负责声音播放逻辑，不参与 tick 编排，由 {@link ApiaryTickHandler} 委托调用。
 * <p>
 * 线程安全：服务端单线程调用，使用 {@link ThreadLocalRandom} 保证随机数线程安全。
 */
public class ApiarySoundHandler {

	/** 播放概率（每 tick），低于 PB 原版 0.005 避免多机器叠加 */
	private static final double SOUND_CHANCE = 0.003D;

	/** 所属方块实体引用 — 用于获取世界和坐标 */
	private final TileEntityMekApiary tile;

	/**
	 * 构造声音处理器
	 *
	 * @param tile 所属方块实体
	 */
	public ApiarySoundHandler(TileEntityMekApiary tile) {
		this.tile = tile;
	}

	/**
	 * 按概率播放蜜蜂工作声音
	 * <br/>
	 * 仅在服务端调用，由 {@link ApiaryTickHandler} 在检测到有蜜蜂工作时调用。
	 * 使用 ThreadLocalRandom 生成随机数，避免多线程竞争。
	 * <p>
	 * Task 1.1 修复：手动应用 MUFFLING 升级音量因子。蜂箱工作声音通过
	 * {@code level.playSound()} 播放，绕过 Mekanism 的
	 * {@code SoundHandler.startTileSound()} 路径，导致 MUFFLING 升级不生效。
	 * 此处按比例降低音量，复刻 MEK 原版 {@code TileTickableSound.getTileVolumeFactor} 逻辑。
	 *
	 * @param workingCount 当前 tick 正在工作的蜜蜂数量（>0 时才调用此方法）
	 */
	public void maybePlayWorkSound(int workingCount) {
		if (workingCount <= 0) return;
		Level level = tile.getLevel();
		if (level == null || level.isClientSide) return;

		if (ThreadLocalRandom.current().nextDouble() < SOUND_CHANCE) {
			// MUFFLING 升级音量因子（0.0=静音，1.0=原音量）
			float volumeFactor = getMufflingFactor();
			if (volumeFactor <= 0.0F) return; // 完全静音，跳过播放
			BlockPos pos = tile.getBlockPos();
			double x = pos.getX() + 0.5D;
			double y = pos.getY();
			double z = pos.getZ() + 0.5D;
			// 音量乘以 MUFFLING 因子，音调保持不变
			level.playSound(null, x, y, z, SoundEvents.BEEHIVE_WORK, SoundSource.BLOCKS, volumeFactor, 1.0F);
		}
	}

	/**
	 * 获取 MUFFLING 升级音量因子
	 * <br/>
	 * 复刻 Mekanism 原版 {@code TileTickableSound.getTileVolumeFactor} 逻辑：
	 * {@code 1.0 - (mufflerCount / max)}（满级=0.0 静音，未安装=1.0 原音量）。
	 * <p>
	 * 蜂箱使用 {@code level.playSound()} 直接播放声音，不走 MEK 的
	 * {@code SoundHandler.startTileSound()} 路径，故 MEK 原生的 MUFFLING
	 * 音量调整逻辑不触发，需在此手动计算并应用。
	 * <p>
	 * 防御性设计：tile 未就绪、不支持 MUFFLING 升级或查询异常时返回 1.0（原音量），
	 * 避免影响正常游戏体验。
	 *
	 * @return 音量因子（0.0~1.0）
	 */
	private float getMufflingFactor() {
		try {
			var component = tile.getComponent();
			// tile 未就绪或不支持 MUFFLING 升级时返回原音量
			if (component == null || !tile.supportsUpgrade(Upgrade.MUFFLING)) return 1.0F;
			int max = Upgrade.MUFFLING.getMax();
			if (max <= 0) return 1.0F;
			int mufflerCount = Math.min(component.getUpgrades(Upgrade.MUFFLING), max);
			return 1.0F - (mufflerCount / (float) max);
		} catch (Exception e) {
			// M9: LogThrottle 节流，避免 tick 路径多台蜂箱叠加刷屏
			LogThrottle.warn("muffling_factor",
					"获取消音因子失败，使用默认音量 (5秒内仅首条输出): {}", e.toString());
			return 1.0F;
		}
	}
}
