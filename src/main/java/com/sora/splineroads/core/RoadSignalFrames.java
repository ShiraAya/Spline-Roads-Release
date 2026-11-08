package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadStructures.Part;
import com.sora.splineroads.core.RoadSurface.Face;
import java.util.*;

/** Retains the current lamp geometry; checks the clock only at its possible change boundaries. */
public final class RoadSignalFrames {
  @FunctionalInterface
  public interface State { int at(Part head, long time); }

  private static final class Head {
    final Part part;
    final List<List<Face>> states = new ArrayList<>(Arrays.asList(null, null, null));
    Head(Part part) { this.part = part; }
    List<Face> faces(int state) {
      var result = states.get(state);
      if (result == null) {
        result = RoadSignalModel.faces(part, true, state);
        states.set(state, result);
      }
      return result;
    }
  }

  private final Head[] heads;
  private final State state;
  private final int[] values;
  private long second;
  private final int clockStep;
  private List<Face> frame;

  public RoadSignalFrames(List<Part> heads, State state) {this(heads,state,20);}
  public RoadSignalFrames(List<Part> heads, State state,int clockStep) {
    if(clockStep!=10&&clockStep!=20)throw new IllegalArgumentException("Invalid signal clock step");
    this.clockStep=clockStep;
    this.heads = heads.stream().map(Head::new).toArray(Head[]::new);
    this.state = state;
    values = new int[heads.size()];
    Arrays.fill(values, -1);
  }

  public List<Face> at(long time) {
    long now = Math.floorDiv(time, clockStep);
    if (frame != null && second == now) return frame;
    boolean changed = frame == null;
    for (int i = 0; i < heads.length; i++) {
      int next = state.at(heads[i].part, time);
      if (next < 0 || next > 2) throw new IllegalArgumentException("Invalid signal state: " + next);
      changed |= values[i] != next;
      values[i] = next;
    }
    if (changed) {
      var result = new ArrayList<Face>();
      for (int i = 0; i < heads.length; i++) result.addAll(heads[i].faces(values[i]));
      frame = List.copyOf(result);
    }
    second = now;
    return frame;
  }
}
