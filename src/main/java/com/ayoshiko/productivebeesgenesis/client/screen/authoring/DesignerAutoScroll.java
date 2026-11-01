package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

/** 中键会话固定滚动目标；直接推进像素位置，停止后不留下滚轮惯性。 */
final class DesignerAutoScroll {
	private static final double DEAD_ZONE = 8;
	private static final double MAX_SPEED = 1800;
	private static final double MAX_FRAME_SECONDS = 0.05;
	private DesignerScroll target;
	private double originX;
	private double originY;
	private int direction;

	boolean start(DesignerScroll candidate, double x, double y) {
		if (active() || candidate == null || candidate.maximum() <= 0 || !Double.isFinite(x) || !Double.isFinite(y)) {
			return false;
		}
		target = candidate;
		target.stop();
		originX = x;
		originY = y;
		direction = 0;
		return true;
	}

	void advance(double pointerY, double seconds) {
		if (!active()) {
			return;
		}
		if (target.maximum() <= 0 || !Double.isFinite(pointerY)) {
			stop();
			return;
		}
		double displacement = pointerY - originY;
		double distance = Math.max(0, Math.abs(displacement) - DEAD_ZONE);
		direction = distance == 0 ? 0 : (int) Math.signum(displacement);
		if (!Double.isFinite(seconds) || seconds <= 0) {
			return;
		}
		double speed = Math.min(MAX_SPEED, distance * 6 + distance * distance * 0.04);
		target.jump(target.position() + direction * speed * Math.min(seconds, MAX_FRAME_SECONDS));
	}

	boolean stop() {
		if (!active()) {
			return false;
		}
		target.stop();
		target = null;
		direction = 0;
		return true;
	}

	boolean active() {
		return target != null;
	}

	boolean targets(DesignerScroll candidate) {
		return target != null && target == candidate;
	}

	double originX() {
		return originX;
	}

	double originY() {
		return originY;
	}

	int direction() {
		return direction;
	}
}
