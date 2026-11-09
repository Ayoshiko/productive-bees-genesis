package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

/** 无分配的动画值；目标改变时保留速度，帧率变化不改变收敛曲线。 */
final class DesignerMotionValue {
    private double position;
    private double velocity;

    double position() {
        return position;
    }

    double velocity() {
        return velocity;
    }

    void snap(double value) {
        position = value;
        velocity = 0;
    }

    void impulse(double value) {
        position += value;
    }

    void advance(double target, double seconds, double duration, boolean animate) {
        if (!animate) {
            snap(target);
            return;
        }
        double time = Math.max(0, Math.min(0.1, seconds));
        double omega = 7 / Math.max(0.06, duration);
        double displacement = position - target;
        double carried = velocity + omega * displacement;
        double decay = Math.exp(-omega * time);
        position = target + (displacement + carried * time) * decay;
        velocity = (velocity - omega * carried * time) * decay;
        if (Math.abs(position - target) < 0.0001 && Math.abs(velocity) < 0.001) {
            snap(target);
        }
    }

    static double easeOut(double progress) {
        double remaining = 1 - Math.max(0, Math.min(1, progress));
        return 1 - remaining * remaining * remaining;
    }
}
