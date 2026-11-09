package com.ayoshiko.productivebeesgenesis.client.screen.authoring;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.Rect2i;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormScreen.GhostTarget;
import com.ayoshiko.productivebeesgenesis.client.screen.authoring.DesignerFormLayout.RowView;

/** 拖放目标随可见行注销，所有入口复用滚动裁剪和过渡坐标。 */
final class DesignerGhostTargets {
    private final DesignerFormScreen screen;
    final List<GhostTarget> targets = new ArrayList<>();

    DesignerGhostTargets(DesignerFormScreen screen) {
        this.screen = screen;
    }

    void add(int x, int y, int w, int h, boolean replacing, Predicate<Object> accepts, Consumer<Object> chosen) {
        targets.add(new GhostTarget(new Rect2i(x, y, w, h), accepts, value -> {
            try {
                chosen.accept(value);
                if (Minecraft.getInstance().screen == screen) {
                    screen.rebuild();
                }
            } catch (RuntimeException exception) {
                screen.status = exception.getMessage();
            }
        }, replacing));
    }

	List<GhostTarget> ghostTargets() {
		return !screen.hasDropdown() && !screen.transition.leaving() ? targets.stream().map(target -> new GhostTarget(screen.screenArea(visibleTargetArea(target)), target.accepts(), target.chosen(), target.replacing()))
				.filter(target -> target.area().getWidth() > 0 && target.area().getHeight() > 0).toList() : List.of();
	}

	Rect2i ghostTargetBounds(Rect2i near) {
		Rect2i nearest = null;
		int distance = Integer.MAX_VALUE;
		for (GhostTarget target : targets) {
			Rect2i area = visibleTargetArea(target);
			if (area.getWidth() <= 0 || area.getHeight() <= 0
					|| near != null && (area.getX() < near.getX() || area.getX() + area.getWidth() > near.getX() + near.getWidth()
							|| area.getY() >= near.getY() + near.getHeight() || area.getY() + area.getHeight() <= near.getY())) {
				continue;
			}
			int offset = near == null ? area.getY() - screen.contentTop : Math.abs(area.getY() - near.getY());
			if (offset < distance) {
				nearest = area;
				distance = offset;
			}
		}
		return nearest;
	}

	Rect2i targetArea(GhostTarget target) {
		for (RowView view : screen.formLayout.rowViews.values()) {
			if (view.targets().contains(target)) {
				int top = Math.max(screen.contentTop, target.area().getY() - (int) Math.floor(screen.formLayout.scroll.position()));
				int bottom = Math.min(screen.editorBottom(), target.area().getY() + target.area().getHeight() - (int) Math.floor(screen.formLayout.scroll.position()));
				return new Rect2i(target.area().getX(), top, target.area().getWidth(), Math.max(0, bottom - top));
			}
		}
		return target.area();
	}

	Rect2i visibleTargetArea(GhostTarget target) {
		Rect2i area = targetArea(target);
		for (RowView view : screen.formLayout.rowViews.values()) {
			if (view.targets().contains(target)) {
				float movement = screen.transition.contentOffsetX();
				int x = Math.max(screen.left, (int) Math.floor(area.getX() + movement));
				int right = Math.min(screen.left + screen.contentWidth, (int) Math.ceil(area.getX() + area.getWidth() + movement));
				return new Rect2i(x, area.getY(), Math.max(0, right - x), area.getHeight());
			}
		}
		return area;
	}


    boolean drop(double x, double y, Object value) {
        for (GhostTarget target : List.copyOf(targets)) {
            if (targetArea(target).contains((int) x, (int) y) && target.accepts().test(value)) {
                target.chosen().accept(value);
                return true;
            }
        }
        return false;
    }
}
