package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

/** 像素滚动由客户端独占；拖动直接定位，滚轮按帧间隔收敛到有界目标。 */
final class DesignerScroll {
	private double position;
	private double target;
	private double maximum;
	private double topFeedback;
	private double bottomFeedback;

	void extent(double contentHeight, double viewportHeight) {
		maximum = Math.max(0, contentHeight - Math.max(0, viewportHeight));
		position = bounded(position);
		target = bounded(target);
	}

	boolean by(double pixels) {
		if (!Double.isFinite(pixels)) {
			return false;
		}
		double next = bounded(target + pixels);
		if (next == target) {
			if (pixels < 0) {
				topFeedback = Math.min(1, topFeedback + 0.25);
			} else {
				bottomFeedback = Math.min(1, bottomFeedback + 0.25);
			}
			return false;
		}
		target = next;
		return true;
	}

	void jump(double pixels) {
		position = bounded(pixels);
		target = position;
	}

	void stop() {
		target = position;
	}

	void advance(double seconds, boolean animated) {
		if (!animated) {
			topFeedback = 0;
			bottomFeedback = 0;
			position = target;
			return;
		}
		if (!Double.isFinite(seconds) || seconds <= 0) {
			return;
		}
		double amount = 1 - Math.exp(-seconds / 0.05);
		topFeedback *= Math.exp(-seconds / 0.12);
		bottomFeedback *= Math.exp(-seconds / 0.12);
		position += (target - position) * amount;
		if (Math.abs(target - position) < 0.01) {
			position = target;
		}
	}

	double position() {
		return position;
	}

	double target() {
		return target;
	}

	double maximum() {
		return maximum;
	}

	double topFeedback() {
		return topFeedback;
	}

	double bottomFeedback() {
		return bottomFeedback;
	}

	private double bounded(double pixels) {
		return Double.isFinite(pixels) ? Math.max(0, Math.min(maximum, pixels)) : 0;
	}
}
