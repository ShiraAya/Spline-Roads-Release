package com.sora.splineroads.world;

import java.util.*;

/** Temporary edit raster ownership. Nested planning scopes release at the outer boundary. */
final class RoadEditRasters implements AutoCloseable {
  private static final ThreadLocal<RoadEditRasters> CURRENT=new ThreadLocal<>();
  private final RoadEditRasters parent;
  private final Set<RoadIndex.Built> roads=Collections.newSetFromMap(new IdentityHashMap<>());
  private boolean closed;
  private RoadEditRasters(){parent=CURRENT.get();CURRENT.set(this);}
  static RoadEditRasters open(){return new RoadEditRasters();}
  static void used(RoadIndex.Built road){var scope=CURRENT.get();if(scope!=null)scope.roads.add(road);}
  int size(){return roads.size();}
  @Override public void close(){
    if(closed)return;
    if(CURRENT.get()!=this)throw new IllegalStateException("Road raster scopes closed out of order");
    closed=true;
    if(parent!=null){parent.roads.addAll(roads);CURRENT.set(parent);}
    else {CURRENT.remove();for(var road:roads)road.releaseEditRaster();}
    roads.clear();
  }
}
