package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.Util;

/** 页面保持不透明，局部过渡可打断；完成动作只由客户端 tick 提交。 */
final class DesignerTransition {
    enum Phase {
        ENTERING, STABLE, LEAVING
    }

    private Phase phase = Phase.STABLE;
    private final DesignerMotionValue movement = new DesignerMotionValue();
    private long started;
    private long frameAt;
    private double tweenStart;
    private Runnable completed;

    void enter() {
        enter(1);
    }

    void enter(int direction) {
        if (phase == Phase.LEAVING) {
            return;
        }
        update();
		if (DesignerPreferences.text("motion_transition").equals("none")) {
			phase = Phase.STABLE;
			movement.snap(0);
			return;
		}
        double amplitude = DesignerPreferences.number("motion_amount");
        if (phase == Phase.STABLE) {
            movement.snap(direction * amplitude);
        }
        tweenStart = movement.position();
        phase = Phase.ENTERING;
        started = Util.getMillis();
        frameAt = started;
        completed = null;
    }

    void leave(Runnable action) {
        if (!DesignerPreferences.animate() || DesignerPreferences.text("motion_transition").equals("none")) {
            action.run();
            return;
        }
        if (phase == Phase.LEAVING) {
            completed = action;
            return;
        }
        update();
        phase = Phase.LEAVING;
        started = Util.getMillis();
        completed = action;
    }

    void tick() {
        update();
        if (phase == Phase.LEAVING && (!DesignerPreferences.animate() || Util.getMillis() - started >= 60)) {
            Runnable next = completed;
            completed = null;
            phase = Phase.STABLE;
            movement.snap(0);
            if (next != null) {
                next.run();
            }
        } else if (phase == Phase.ENTERING && Math.abs(movement.position()) < 0.02) {
            phase = Phase.STABLE;
            movement.snap(0);
        }
    }

    private void update() {
        long now = Util.getMillis();
        double seconds = frameAt == 0 ? 0 : Math.max(0, now - frameAt) / 1000.0;
        frameAt = now;
        if (!DesignerPreferences.animate()) {
            movement.snap(0);
            return;
        }
        double duration = DesignerPreferences.number("animation_ms") / 1000.0;
        if (DesignerPreferences.text("motion_style").equals("smooth") && phase == Phase.ENTERING) {
            double progress = (now - started) / (duration * 1000);
            movement.snap(tweenStart * (1 - DesignerMotionValue.easeOut(progress)));
        } else {
            movement.advance(0, seconds, duration, true);
        }
    }

    float visibility() {
        return 1;
    }

    float contentOffset() {
        return 0;
    }

    float contentOffsetX() {
        update();
		return DesignerPreferences.text("motion_transition").equals("slide") ? (float) movement.position() : 0;
    }

	float reveal() {
		update();
		if (!DesignerPreferences.animate() || !DesignerPreferences.text("motion_transition").equals("reveal") || phase != Phase.ENTERING) {
			return 1;
		}
		return (float) (0.9 + 0.1 * DesignerMotionValue.easeOut((Util.getMillis() - started) / DesignerPreferences.number("animation_ms")));
	}

    boolean leaving() {
        return phase == Phase.LEAVING;
    }

    void settle() {
        // 输入立即可用，命中坐标与当前动画位置一致，无需跳到终点。
    }

    void cancel() {
        completed = null;
        phase = Phase.STABLE;
        movement.snap(0);
    }
}
