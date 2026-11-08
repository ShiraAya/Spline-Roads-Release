package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.Locale;

/** Grade policy for newly planned ramps; old saved roads remain loadable. */
public final class RoadGrades {
  public static final double MAX_RAMP_GRADE = .15;

  public static double maximum(Mesh mesh) {
    double max = 0;
    for (int i = 1; i < mesh.samples().size(); i++) {
      V d = mesh.samples().get(i).center().sub(mesh.samples().get(i - 1).center());
      max = Math.max(max, Math.abs(d.y()) / d.horizontalLength());
    }
    return max;
  }

  public static void validate(Mesh mesh) {
    if (!mesh.settings().style().ramp()) return;
    double grade = maximum(mesh);
    if (grade > MAX_RAMP_GRADE + 1e-7)
      throw new IllegalArgumentException(
          String.format(Locale.ROOT, "匝道最大纵坡 %.1f%%，超过 15%% 上限；请延长爬坡段或降低高差", grade * 100));
  }

  private RoadGrades() {}
}
