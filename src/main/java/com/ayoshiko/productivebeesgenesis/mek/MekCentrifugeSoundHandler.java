package com.ayoshiko.productivebeesgenesis.mek;

import com.ayoshiko.productivebeesgenesis.util.LogThrottle;
import mekanism.api.Upgrade;
import mekanism.common.registries.MekanismSounds;
import mekanism.common.tile.base.TileEntityMekanism;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;

import java.util.concurrent.ThreadLocalRandom;

/**
	 * MEK 离心机工作声音处理器 — 服务端按概率播放电力熔炼炉声（ENERGIZED_SMELTER），
	 * 基础音量降低并手动应用 MUFFLING 升级因子。
	 * <p>
	 * 为什么不再用 {@code Machine.MachineBuilder.withSound(ENERGIZED_SMELTER)} 的 Mekanism 客户端循环声
	 * （与 {@link com.ayoshiko.productivebeesgenesis.apiary.ApiarySoundHandler} 同一架构决策）：
	 * <ul>
	 *   <li><b>残留满音量循环（"降噪突然失效" 根因）：</b>Mekanism 客户端 {@code SoundHandler.TileTickableSound}
	 *       是 looping 实例，{@code tick()} 不检测方块实体已被破坏（只检查 level 为空 / 玩家超出范围），
	 *       且 {@code getTileVolumeFactor()} 在该坐标找不到 tile 时返回 1.0（满音量）。因此玩家破坏相邻
	 *       机器后，被破坏机器的循环声会在下一个 checkInterval（20-40 刻）重算成满音量继续循环，
	 *       直到玩家走远或重登才消失——表现为"消音升级突然不起作用，退游戏重进就好"。</li>
	 *   <li><b>刺耳：</b>循环声基础音量为 {@code getInitialVolume()(=1.0) × baseSoundVolume}，没有 MUFFLING
	 *       升级时持续满音量播放。</li>
	 * </ul>
	 * 改为服务端一次性 {@code level.playSound}：一次性声音自然结束、方块移除后不残留；MUFFLING 因子
	 * 在服务端每次播放时权威计算并烘焙进声音包，永不客户端失步；基础音量用 {@link #BASE_VOLUME} 调低。
	 * <p>
	 * 设计原则（SRP）：仅负责声音播放，不参与 tick 编排，由 {@link MekCentrifugeTickHandler}（基础机）
	 * 与 {@link AbstractMekCentrifugeFactory}（工厂）委托调用。
	 * <p>
	 * 线程安全：服务端单线程调用，使用 {@link ThreadLocalRandom} 保证随机数线程安全。
	 */
public class MekCentrifugeSoundHandler {

	/** 播放概率（每 gameTick）— 控制机器声密度，低于 Mekanism 连续循环以降低刺耳感 */
	private static final double SOUND_CHANCE = 0.02D;

	/** 基础音量（未安装 MUFFLING 时）— 由 1.0 降至 0.4，缓解 ENERGIZED_SMELTER 满音量刺耳 */
	private static final float BASE_VOLUME = 0.4F;

	/** 所属方块实体引用 — 基础机与工厂均为 TileEntityMekanism 子类，用于获取世界/坐标/升级组件 */
	private final TileEntityMekanism tile;
	/** 时间加速器会在同一 gameTime 重复调用，只允许一次随机声音尝试。 */
	private final GameTickGate soundAttemptGate = new GameTickGate();

	/**
	 * 构造声音处理器
	 *
	 * @param tile 所属方块实体（基础离心机或离心机工厂）
	 */
	public MekCentrifugeSoundHandler(TileEntityMekanism tile) {
		this.tile = tile;
	}

	/**
	 * 机器处于激活状态时按概率播放一次离心机工作声。
	 * <br/>
	 * 仅在服务端调用；{@link GameTickGate} 去重时间加速器的同 gameTick 重复调用，
	 * 使加速倍率不改变声音密度（对齐 {@link com.ayoshiko.productivebeesgenesis.apiary.ApiarySoundHandler}）。
	 *
	 * @param active 机器本 tick 是否在处理配方（false 时不发声）
	 */
	public void maybePlayWorkSound(boolean active) {
		if (!active) return;
		Level level = tile.getLevel();
		if (level == null || level.isClientSide) return;
		if (!soundAttemptGate.tryEnter(level.getGameTime())) return;
		if (ThreadLocalRandom.current().nextDouble() >= SOUND_CHANCE) return;

		// MUFFLING 升级音量因子（0.0=静音，1.0=原音量），再乘降噪后的基础音量
		float volume = BASE_VOLUME * getMufflingFactor();
		if (volume <= 0.0F) return; // 满级 MUFFLING 完全静音，跳过播放
		BlockPos pos = tile.getBlockPos();
		level.playSound(null, pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D,
				MekanismSounds.ENERGIZED_SMELTER.get(), SoundSource.BLOCKS, volume, 1.0F);
	}

	/**
	 * 获取 MUFFLING 升级音量因子
	 * <br/>
	 * 复刻 Mekanism 原版 {@code TileTickableSound.getTileVolumeFactor} 逻辑：
	 * {@code 1.0 - (mufflerCount / max)}（满级=0.0 静音，未安装=1.0 原音量）。
	 * <p>
	 * 防御性设计：tile 未就绪、不支持 MUFFLING 升级或查询异常时返回 1.0（原音量），避免影响正常体验。
	 *
	 * @return 音量因子（0.0~1.0）
	 */
	private float getMufflingFactor() {
		try {
			var component = tile.getComponent();
			if (component == null || !tile.supportsUpgrade(Upgrade.MUFFLING)) return 1.0F;
			int max = Upgrade.MUFFLING.getMax();
			if (max <= 0) return 1.0F;
			int mufflerCount = Math.min(component.getUpgrades(Upgrade.MUFFLING), max);
			return 1.0F - (mufflerCount / (float) max);
		} catch (Exception e) {
			// LogThrottle 节流，避免 tick 路径多台离心机叠加刷屏
			LogThrottle.warn("mek_centrifuge_muffling_factor",
					"获取离心机消音因子失败，使用默认音量 (5秒内仅首条输出): {}", e.toString());
			return 1.0F;
		}
	}
}
