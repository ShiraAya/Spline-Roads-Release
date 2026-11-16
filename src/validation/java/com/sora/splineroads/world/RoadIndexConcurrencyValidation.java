package com.sora.splineroads.world;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import java.lang.reflect.Field;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.*;

/** Runs against actual RoadIndex and Minecraft VoxelShape classes, without a world. */
public final class RoadIndexConcurrencyValidation {
  private static final BlockPos FIRST = new BlockPos(8, 63, 0);
  private static final BlockPos SECOND = new BlockPos(9, 63, 0);
  private static final BlockPos MISS = new BlockPos(10, 63, 0);
  private static final boolean LEGACY = Boolean.getBoolean("sr201.expectLegacy");
  private static int checks;

  private static final class Gate extends LinkedHashMap<Long, List<RoadRaster.Box>> {
    final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    Gate() { super(128, .75f, true); }
    @Override public List<RoadRaster.Box> computeIfAbsent(Long key,
        Function<? super Long, ? extends List<RoadRaster.Box>> function) {
      return super.computeIfAbsent(key, k -> {
        if (k == MISS.asLong()) {
          entered.countDown();
          await(release, "release raster calculation");
        }
        return function.apply(k);
      });
    }
  }

  private static RoadRecord road(double thickness) {
    V a = new V(.5, 64, .5), b = new V(64.5, 64, .5);
    var settings = new Settings(Mode.STRAIGHT, Style.O4_YELLOW, 40, 1, thickness, 90)
        .options(RoadProfile.Options.DEFAULT.extras(false, false, false));
    var first = new Node(a, RoadPlanner.yaw(b.sub(a)), 0);
    return new RoadRecord(new UUID(0, 201), new UUID(0, 0),
        BlockPos.containing(a.x(), a.y(), a.z()), BlockPos.containing(b.x(), b.y(), b.z()),
        first, new Node(b, first.yaw(), 0), settings, false, 4, List.of(), 0, 0);
  }

  private static Gate gate(RoadIndex.Built built) throws Exception {
    var gate = new Gate();
    Field field = RoadIndex.Built.class.getDeclaredField("nearbyCells");
    field.setAccessible(true);
    field.set(built, gate);
    return gate;
  }

  private static void await(CountDownLatch latch, String detail) {
    try { check(latch.await(5, TimeUnit.SECONDS), "timeout: " + detail); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
  }

  private static Thread worker(String name, Runnable operation, AtomicReference<Throwable> error) {
    var result = new Thread(() -> { try { operation.run(); } catch (Throwable t) { error.set(t); } }, name);
    result.setDaemon(true);
    return result;
  }

  /** Wait for a defined lock state, rather than hoping a race happens during a sleep. */
  private static void attempted(Thread actor) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (actor.isAlive() && actor.getState() != Thread.State.BLOCKED && System.nanoTime() < end)
      Thread.sleep(1);
    if (LEGACY) { actor.join(5000); check(!actor.isAlive(), "legacy second operation must finish during miss"); }
    else check(actor.isAlive() && actor.getState() == Thread.State.BLOCKED,
        "second operation must wait for the shared collision lock");
  }

  private static void concurrent(String action) throws Exception {
    var index = new RoadIndex(true);
    var record = road(.35);
    var built = new RoadIndex.Built(record, true);
    Gate gate = gate(built);
    index.put(built);
    check(!index.shape(FIRST).isEmpty() && !index.shape(SECOND).isEmpty(), "real primed road geometry");
    var missError = new AtomicReference<Throwable>();
    var actorError = new AtomicReference<Throwable>();
    var miss = worker("Render thread", () -> index.shape(MISS), missError);
    Runnable operation = switch (action) {
      case "hit" -> () -> check(!index.shape(FIRST).isEmpty(), "cached hit preserves geometry");
      case "remove" -> () -> index.remove(record.id());
      case "clear" -> index::clear;
      case "replace" -> () -> index.put(new RoadIndex.Built(road(.15), true));
      default -> throw new AssertionError(action);
    };
    var actor = worker("CullThread-" + action, operation, actorError);
    miss.start();
    try { await(gate.entered, "real shape miss"); actor.start(); attempted(actor); }
    finally { gate.release.countDown(); miss.join(5000); actor.join(5000); }
    check(!miss.isAlive() && !actor.isAlive(), "threads terminate: " + action);
    check(actorError.get() == null, "actor failed: " + actorError.get());
    if (LEGACY) check(missError.get() instanceof ConcurrentModificationException,
        "baseline must reproduce CME for " + action + ": " + missError.get());
    else {
      check(missError.get() == null, "shape miss failed: " + missError.get());
      if (action.equals("remove") || action.equals("clear"))
        check(index.shape(MISS).isEmpty(), "removed geometry cannot be republished after invalidation");
      if (action.equals("replace")) {
        var expected = RoadCollisionShape.build(index.roads.get(record.id()).boxes(MISS));
        check(!Shapes.joinIsNotEmpty(index.shape(MISS), expected, BooleanOp.NOT_SAME),
            "replacement shape cannot retain the previous generation");
      }
    }
    System.out.println((LEGACY ? "EXPECTED LEGACY CME " : "PASS ") + action);
  }

  private static void directBoxes() throws Exception {
    var built = new RoadIndex.Built(road(.35), true);
    Gate gate = gate(built);
    built.boxes(FIRST); built.boxes(SECOND);
    var missError = new AtomicReference<Throwable>(); var hitError = new AtomicReference<Throwable>();
    var miss = worker("Direct raster miss", () -> built.boxes(MISS), missError);
    var hit = worker("Direct raster hit", () -> built.boxes(FIRST), hitError);
    miss.start();
    try { await(gate.entered, "direct raster miss"); hit.start(); attempted(hit); }
    finally { gate.release.countDown(); miss.join(5000); hit.join(5000); }
    check(!miss.isAlive() && !hit.isAlive(), "direct raster queries terminate");
    check(hitError.get() == null, "direct raster hit failed");
    check(LEGACY ? missError.get() instanceof ConcurrentModificationException : missError.get() == null,
        "direct raster result: " + missError.get());
    System.out.println(LEGACY ? "EXPECTED LEGACY CME direct boxes" : "PASS direct boxes");
  }

  @SuppressWarnings("unchecked") private static void boundedAndInvalidated() throws Exception {
    var index = new RoadIndex(true);
    for (int i = 0; i < 33000; i++) index.shape(new BlockPos(i, 0, 0));
    Field shapesField = RoadIndex.class.getDeclaredField("shapes"); shapesField.setAccessible(true);
    Field chunksField = RoadIndex.class.getDeclaredField("shapeChunks"); chunksField.setAccessible(true);
    var shapes = (Map<Long, VoxelShape>) shapesField.get(index);
    var chunks = (Map<Long, Set<Long>>) chunksField.get(index);
    check(shapes.size() == 32768, "LRU bound remains 32768");
    check(!shapes.containsKey(new BlockPos(0, 0, 0).asLong()), "oldest entry evicted");
    var tracked = new HashSet<Long>(); chunks.values().forEach(tracked::addAll);
    check(tracked.equals(shapes.keySet()), "eviction keeps chunk tracking exact");
    index.clear();
    check(shapes.isEmpty() && chunks.isEmpty(), "clear removes shapes and tracking");
  }

  private static void check(boolean value, String message) {
    if (!value) throw new AssertionError(message);
    checks++;
  }

  public static void main(String[] args) throws Exception {
    for (String action : List.of("hit", "remove", "clear", "replace")) concurrent(action);
    directBoxes();
    boundedAndInvalidated();
    System.out.println("RoadIndexConcurrencyValidation " + (LEGACY ? "RED REPRODUCTION" : "GREEN") + " checks=" + checks);
  }
}

