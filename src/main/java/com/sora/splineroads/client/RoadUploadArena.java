package com.sora.splineroads.client;

import com.mojang.blaze3d.vertex.*;

/** Render-thread native scratch storage, reused for every material, region and lighting refresh. */
final class RoadUploadArena {
  private BufferBuilder builder;
  private final int initialCapacity;

  RoadUploadArena(){this(4096);}
  RoadUploadArena(int initialCapacity){this.initialCapacity=initialCapacity;}
  boolean allocated(){return builder!=null;}

  BufferBuilder begin(){return begin(DefaultVertexFormat.NEW_ENTITY);}
  BufferBuilder begin(VertexFormat format) {
    if (builder == null) builder = new BufferBuilder(initialCapacity);
    // A failed producer may have left an unfinished batch. It must not pin another batch's bytes.
    if (builder.building()) builder.end().release();
    builder.discard();
    builder.begin(VertexFormat.Mode.QUADS, format);
    return builder;
  }
}
