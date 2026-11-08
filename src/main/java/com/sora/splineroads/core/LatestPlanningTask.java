package com.sora.splineroads.core;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** One running and at most one latest queued preview. Cancellation is cooperative,
 * and never publishes a cancelled result as a geometry error. */
public final class LatestPlanningTask<T> {
  private static final class Request {
    final AtomicBoolean cancelled = new AtomicBoolean();
    FutureTask<Void> task;
  }
  private final ThreadPoolExecutor executor;
  private final String phase;
  private Request current;

  public LatestPlanningTask(String name) {
    phase = name;
    executor = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(1), task -> {
          Thread thread = new Thread(task, name);
          thread.setDaemon(true);
          return thread;
        });
    executor.allowCoreThreadTimeOut(true);
  }

  public synchronized void cancel() {
    Request previous = current;
    current = null;
    if (previous != null) {
      previous.cancelled.set(true);
      previous.task.cancel(true);
      executor.purge();
    }
  }

  public synchronized void submit(Callable<T> computation, BiConsumer<T, Throwable> completion) {
    cancel();
    Request request = new Request();
    current = request;
    request.task = new FutureTask<>(() -> {
      T result = null;
      Throwable failure = null;
      try (var scope = RoadPlanningBudget.cancellable(phase, request.cancelled::get)) {
        RoadPlanningBudget.check();
        result = computation.call();
        RoadPlanningBudget.check();
      } catch (Exception error) {
        failure = error;
      } finally {
        RoadQueries.clearThreadCache();
      }
      if (!request.cancelled.get()) completion.accept(result, failure);
      synchronized (LatestPlanningTask.this) {
        if (current == request) current = null;
      }
      return null;
    });
    executor.execute(request.task);
  }

  public int queued() { return executor.getQueue().size(); }
}
