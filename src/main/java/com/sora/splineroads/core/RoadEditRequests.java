package com.sora.splineroads.core;

import java.util.Objects;
import java.util.UUID;

/** One editor owns its latest submission. Late replies never acquire a new editor. */
public final class RoadEditRequests {
  public record Request(UUID id, long generation, String action, String target, Object owner) {}
  private Object editor;
  private long generation;
  private Request pending;
  private Request completed;

  public void open(Object owner) {
    if (editor != owner) { editor = owner; generation++; pending = null; completed = null; }
  }

  /** A hidden editor keeps its preview until another edit explicitly takes over. */
  public boolean owns(Object owner) { return owner != null && editor == owner; }

  public boolean changed(Object owner) {
    if (editor != owner) return false;
    boolean wasPending = pending != null;
    generation++; pending = null; completed = null;
    return wasPending;
  }

  public Request submit(Object owner, String action, String target) {
    open(owner);
    completed = null;
    pending = new Request(UUID.randomUUID(), ++generation, action, target, owner);
    return pending;
  }

  public Request take(UUID id, long revision, String action, String target) {
    Request value = pending;
    if (value == null || !value.id().equals(id) || value.generation() != revision
        || value.generation() != generation || value.owner() != editor
        || !value.action().equals(action) || !Objects.equals(value.target(), target)) return null;
    pending = null;
    completed = value;
    return value;
  }

  /** A bounded receipt for optional geometry arriving after the small result.
   * Reading it never completes another command or acquires another editor. */
  public Request completed(UUID id,long revision,String action,String target){
    Request value=completed;
    return value!=null&&value.id().equals(id)&&value.generation()==revision&&value.generation()==generation
        &&value.owner()==editor&&value.action().equals(action)&&Objects.equals(value.target(),target)?value:null;
  }

  public void reset() { editor = null; generation++; pending = null; completed = null; }
}
