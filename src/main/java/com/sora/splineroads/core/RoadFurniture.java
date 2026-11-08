package com.sora.splineroads.core;

/** Continuous furniture stationing. The origin is relative to the saved road start node. */
public final class RoadFurniture {
  public record Phase(double origin, int direction) {
    public static final Phase DEFAULT = new Phase(0, 1);
    public Phase {
      if (!Double.isFinite(origin) || Math.abs(direction) != 1)
        throw new IllegalArgumentException("无效的道路设施间距基准");
      if(Math.abs(origin)>1e12)throw new IllegalArgumentException("设施间距基准超出范围");
    }
    public Phase shift(double distance) { return new Phase(origin + direction * distance, direction); }
    public double first(double offset, double spacing) {
      return modulo((offset - origin) * direction, spacing);
    }
  }
  public static double modulo(double value, double period) {
    double r = value - Math.floor(value / period) * period;
    return r < 1e-7 || period - r < 1e-7 ? 0 : r;
  }
  private RoadFurniture() {}
}
