package com.sora.splineroads.client;

import java.util.Queue;

/** The vanilla render-thread upload queue, not a worker executor. */
public interface RoadChunkUploads {
  Queue<Runnable> splineroads$uploads();
}
