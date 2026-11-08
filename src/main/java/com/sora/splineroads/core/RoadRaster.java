package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/**
 * Conservative quarter-block XZ footprint (eighth-block on slopes), retaining top/bottom heights.
 * Every triangle is clipped before rasterizing; negative coordinates use floor throughout.
 */
public final class RoadRaster {
  public record Cell(int x, int y, int z) {}

  public record Box(double x0, double y0, double z0, double x1, double y1, double z1) {}

  private record Tile(int x, int z) {}

  private static final class Interval {
    double low, high;

    Interval(double a, double b) {
      low = a;
      high = b;
    }
  }

  /** Immutable four-block spatial bins for nearby collision queries. Fine geometry is unchanged. */
  public static final class Local {
    private record Segment(V l, V r, V ll, V rr, double thickness) {}

    private final Map<Long, List<Segment>> decks = new HashMap<>();
    private final Map<Long, List<RoadStructures.Part>> furniture = new HashMap<>();
    private final int resolution;
    public final int segmentCount;

    public Local(Mesh mesh, List<RoadStructures.Part> parts) {
      resolution = mesh.max().y() - mesh.min().y() - mesh.settings().thickness() > 1e-5 ? 8 : 4;
      int count = 0;
      for (Mesh piece : LaneDeck.rasterPieces(mesh,96)) {
        var points = piece.samples();
        for (int i = 1; i < points.size(); i++) {
      RoadPlanningBudget.check();
          Sample a = points.get(i - 1), b = points.get(i);
          for(var strip:LaneDeck.strips(piece,a,b)) {
          Segment segment=new Segment(strip.al(),strip.ar(),strip.bl(),strip.br(),mesh.settings().thickness());
          var vertices=List.of(segment.l,segment.r,segment.ll,segment.rr);
          add(decks,segment,vertices.stream().mapToDouble(V::x).min().orElseThrow(),vertices.stream().mapToDouble(V::z).min().orElseThrow(),
              vertices.stream().mapToDouble(V::x).max().orElseThrow(),vertices.stream().mapToDouble(V::z).max().orElseThrow());count++;
          }
        }
      }
      segmentCount = count;
      for (var p : parts) {
        if(RailbedFurniture.visualOnly(p))continue;
        double w = p.halfExtent();
        add(
            furniture,
            p,
            Math.min(p.a().x(), p.b().x()) - w,
            Math.min(p.a().z(), p.b().z()) - w,
            Math.max(p.a().x(), p.b().x()) + w,
            Math.max(p.a().z(), p.b().z()) + w);
      }
    }

    private static long key(int x, int z) {
      return ((long) x << 32) ^ (z & 0xffffffffL);
    }

    private static <T> void add(
        Map<Long, List<T>> bins, T value, double minX, double minZ, double maxX, double maxZ) {
      for (int x = (int) Math.floor(minX / 4); x <= (int) Math.floor(maxX / 4); x++)
        for (int z = (int) Math.floor(minZ / 4); z <= (int) Math.floor(maxZ / 4); z++)
          bins.computeIfAbsent(key(x, z), ignored -> new ArrayList<>()).add(value);
    }

    public int candidates(Cell cell) {
      return decks
          .getOrDefault(key(Math.floorDiv(cell.x(), 4), Math.floorDiv(cell.z(), 4)), List.of())
          .size();
    }

    /** Legacy migration enumerates only the requested chunk, never the whole saved road. */
    public Set<Cell> cellsInChunk(int chunkX, int chunkZ) {
      Set<Cell> result = new HashSet<>();
      for (int x = chunkX * 16; x < chunkX * 16 + 16; x++)
        for (int z = chunkZ * 16; z < chunkZ * 16 + 16; z++) {
          long bin = key(Math.floorDiv(x, 4), Math.floorDiv(z, 4));
          result.addAll(deckColumn(x,z).keySet());
          for (var part : furniture.getOrDefault(bin, List.of())) {
            double w = part.halfExtent();
            if (x + 1 <= Math.min(part.a().x(),part.b().x()) - w || x >= Math.max(part.a().x(),part.b().x()) + w
                || z + 1 <= Math.min(part.a().z(),part.b().z()) - w || z >= Math.max(part.a().z(),part.b().z()) + w) continue;
            int low = (int)Math.floor(Math.min(part.a().y(),part.b().y()) - part.verticalFrame());
            int high = (int)Math.ceil(Math.max(part.a().y(),part.b().y()) + part.height() + part.verticalFrame());
            for (int y = low; y < high; y++) {
              Cell cell = new Cell(x,y,z);
              if (!structures(List.of(part),cell).isEmpty()) result.add(cell);
            }
          }
        }
      return result;
    }

    public Map<Cell,List<Box>> deckColumn(int x,int z) {
      Map<Tile,List<Interval>> tiles=new HashMap<>();
      Cell column=new Cell(x,0,z);
      for(var s:decks.getOrDefault(key(Math.floorDiv(x,4),Math.floorDiv(z,4)),List.of())) {
        triangle(tiles,s.l,s.r,s.rr,s.thickness,resolution,column);
        triangle(tiles,s.l,s.rr,s.ll,s.thickness,resolution,column);
      }
      return collect(tiles,resolution,null);
    }

    /** Narrow-phase structural columns for sidewalk headroom; never rasterizes a whole road. */
    public Map<Cell,List<Box>> structureColumn(int x,int z,java.util.function.Predicate<RoadStructures.Part> match) {
      Map<Cell,List<Box>> result=new HashMap<>();
      for(var part:furniture.getOrDefault(key(Math.floorDiv(x,4),Math.floorDiv(z,4)),List.of())) {
        if(!match.test(part))continue;
        double extent=part.halfExtent();
        if(x+1<=Math.min(part.a().x(),part.b().x())-extent||x>=Math.max(part.a().x(),part.b().x())+extent
            ||z+1<=Math.min(part.a().z(),part.b().z())-extent||z>=Math.max(part.a().z(),part.b().z())+extent)continue;
        int low=(int)Math.floor(Math.min(part.a().y(),part.b().y())-part.verticalFrame());
        int high=(int)Math.ceil(Math.max(part.a().y(),part.b().y())+part.height()+part.verticalFrame());
        for(int y=low;y<high;y++) {
          Cell cell=new Cell(x,y,z);
          structures(List.of(part),cell).forEach((c,boxes)->result.computeIfAbsent(c,k->new ArrayList<>()).addAll(boxes));
        }
      }
      return result;
    }

    public boolean structureAt(Cell cell,java.util.function.Predicate<RoadStructures.Part> match) {
      for(var part:furniture.getOrDefault(key(Math.floorDiv(cell.x(),4),Math.floorDiv(cell.z(),4)),List.of()))
        if(match.test(part)&&!structures(List.of(part),cell).isEmpty())return true;
      return false;
    }

    public List<Box> boxes(Cell cell) {
      long key = key(Math.floorDiv(cell.x(), 4), Math.floorDiv(cell.z(), 4));
      Map<Tile, List<Interval>> tiles = new HashMap<>();
      for (var s : decks.getOrDefault(key, List.of())) {
        double top = Math.max(Math.max(s.l.y(), s.r.y()), Math.max(s.ll.y(), s.rr.y()));
        double bottom =
            Math.min(Math.min(s.l.y(), s.r.y()), Math.min(s.ll.y(), s.rr.y())) - s.thickness;
        if (cell.y() >= top || cell.y() + 1 <= bottom) continue;
        triangle(tiles, s.l, s.r, s.rr, s.thickness, resolution, cell);
        triangle(tiles, s.l, s.rr, s.ll, s.thickness, resolution, cell);
      }
      List<Box> boxes =
          new ArrayList<>(collect(tiles, resolution, cell).getOrDefault(cell, List.of()));
      boxes.addAll(
          structures(furniture.getOrDefault(key, List.of()), cell).getOrDefault(cell, List.of()));
      return List.copyOf(boxes);
    }
  }

  /** Exact immutable shape cache between preview, commit and immediate delete.
   * No world/ownership/blocks are cached: every transaction still revalidates them.
   * Value equality covers the complete Mesh (including reference, cuts and settings),
   * so a one-coordinate/cut/thickness change is a miss, not stale collision geometry.
   * Strong retention is bounded by both entries and actual boxes, not just road count. */
  private static final class ShapeKey {
    final Mesh mesh;final int hash;
    ShapeKey(Mesh mesh){this.mesh=mesh;hash=mesh.hashCode();}
    public int hashCode(){return hash;}
    public boolean equals(Object o){return this==o||o instanceof ShapeKey k&&mesh.equals(k.mesh);}
  }
  private record SavedRaster(Map<Cell,List<Box>> cells,int weight){}
  private static final LinkedHashMap<ShapeKey,SavedRaster> SAVED=new LinkedHashMap<>(16,.75f,true);
  private static long savedWeight,savedHits,savedMisses,savedEpoch;
  public static synchronized void clearSavedRasters(){SAVED.clear();savedWeight=0;savedEpoch++;}
  public static synchronized long[] savedRasterStats(){return new long[]{SAVED.size(),savedWeight,savedHits,savedMisses};}
  public static Map<Cell,List<Box>> cachedRaster(Mesh mesh){
    var key=new ShapeKey(mesh);long epoch;
    synchronized(RoadRaster.class){var old=SAVED.get(key);if(old!=null){savedHits++;return old.cells();}savedMisses++;epoch=savedEpoch;}
    var cells=raster(mesh);long weight=mesh.samples().size();
    for(var list:cells.values())weight+=list.size()+1;
    if(weight>250_000)return cells;
    cells.replaceAll((cell,boxes)->List.copyOf(boxes));var result=Map.copyOf(cells);
    synchronized(RoadRaster.class){
      if(epoch!=savedEpoch)return result;
      var old=SAVED.get(key);if(old!=null)return old.cells();
      SAVED.put(key,new SavedRaster(result,(int)weight));savedWeight+=weight;
      var it=SAVED.entrySet().iterator();while(it.hasNext()&&(SAVED.size()>12||savedWeight>250_000)){savedWeight-=it.next().getValue().weight();it.remove();}
    }
    return result;
  }

  public static Map<Cell, List<Box>> raster(Mesh mesh) {
    return raster(mesh, null);
  }

  public static Map<Cell, List<Box>> raster(Mesh mesh, Cell only) {
    int resolution = mesh.max().y() - mesh.min().y() - mesh.settings().thickness() > 1e-5 ? 8 : 4;
    if (mesh.length() <= 256) return rasterPart(mesh, only, resolution);
    // Rasterize long roads in bounded strips without retaining millions of sub-block tiles.
    Map<Cell, List<Box>> result = new HashMap<>();
    for (Mesh part : LaneDeck.rasterPieces(mesh,96)) {
      RoadPlanningBudget.check();
      if (only != null
          && (only.x() + 1 < part.min().x()
              || only.x() > part.max().x()
              || only.z() + 1 < part.min().z()
              || only.z() > part.max().z())) continue;
      rasterPart(part, only, resolution)
          .forEach(
              (cell, boxes) ->
                  result.computeIfAbsent(cell, ignored -> new ArrayList<>()).addAll(boxes));
      if (result.size() > RoadLimits.MAX_BODY_CELLS)
        throw new IllegalArgumentException("道路实体占位过大，请增加中间端点");
    }
    result.replaceAll((cell, boxes) -> compact(boxes));
    return result;
  }

  private static Map<Cell, List<Box>> rasterPart(Mesh mesh, Cell only, int resolution) {
    if(!Boolean.getBoolean("sr.raster.legacyRectangles")){
      var rectangular=flatRectangles(mesh,only,resolution);
      if(rectangular!=null)return rectangular;
    }
    Map<Tile, List<Interval>> tiles = new HashMap<>();
    var points = mesh.samples();
    double step = 1.0 / resolution;
    for (int i = 1; i < points.size(); i++) {
      RoadPlanningBudget.check();
      Sample a = points.get(i - 1), b = points.get(i);
      for(var strip:LaneDeck.strips(mesh,a,b)) {
        triangle(tiles,strip.al(),strip.ar(),strip.br(),mesh.settings().thickness(),resolution,only);
        triangle(tiles,strip.al(),strip.br(),strip.bl(),mesh.settings().thickness(),resolution,only);
      }
      if (tiles.size() > 1500000) throw new IllegalArgumentException("碰撞面积过大，请将道路分为多段");
    }
    return collect(tiles, resolution, only);
  }

  /** Exact flat axis-aligned strips use a per-cell occupancy mask instead of
   * allocating/clipping sixteen or sixty-four Tile/Interval objects per block.
   * Nonrectangles, crossfall and numerically tiny boundary slivers use the original
   * triangle path. All samples/cuts are retained; this is NOT coarser collision. */
  private static Map<Cell,List<Box>> flatRectangles(Mesh mesh,Cell only,int resolution){
    var points=mesh.samples(); if(points.isEmpty())return null;
    double y=points.get(0).center().y();
    for(var at:points)if(at.center().y()!=y||at.left().y()!=0)return null;
    var quads=new ArrayList<double[]>();
    for(int i=1;i<points.size();i++)for(var q:LaneDeck.strips(mesh,points.get(i-1),points.get(i))){
      double[] rect=rectangle(q.al(),q.ar(),q.br(),q.bl(),resolution);
      if(rect==null)return null;
      quads.add(rect);
    }
    // A quarter-grid mask fits in one long even for eighth-grid input.
    Map<Tile,Long> masks=new HashMap<>();
    for(var r:quads){
      RoadPlanningBudget.check();
      int tx0=(int)Math.floor(r[0]*resolution),tx1=(int)Math.ceil(r[2]*resolution)-1;
      int tz0=(int)Math.floor(r[1]*resolution),tz1=(int)Math.ceil(r[3]*resolution)-1;
      int x0=Math.floorDiv(tx0,resolution),x1=Math.floorDiv(tx1,resolution);
      int z0=Math.floorDiv(tz0,resolution),z1=Math.floorDiv(tz1,resolution);
      if(only!=null){x0=Math.max(x0,only.x);x1=Math.min(x1,only.x);z0=Math.max(z0,only.z);z1=Math.min(z1,only.z);}
      for(int x=x0;x<=x1;x++)for(int z=z0;z<=z1;z++){
        int loX=Math.max(0,tx0-x*resolution),hiX=Math.min(resolution,tx1-x*resolution+1);
        int loZ=Math.max(0,tz0-z*resolution),hiZ=Math.min(resolution,tz1-z*resolution+1);
        long row=((1L<<(hiX-loX))-1)<<loX,bits=0;
        for(int iz=loZ;iz<hiZ;iz++)bits|=row<<(iz*resolution);
        var key=new Tile(x,z);masks.merge(key,bits,(a,b)->a|b);
      }
    }
    Map<Cell,List<Box>> result=new HashMap<>();
    double low=y-mesh.settings().thickness();int y0=(int)Math.floor(low),y1=(int)Math.ceil(y-1e-8)-1;
    for(var e:masks.entrySet())for(int iy=y0;iy<=y1;iy++){
      if(only!=null&&only.y!=iy)continue;
      double b0=Math.max(0,low-iy),b1=Math.min(1,y-iy);if(b1-b0<=1e-8)continue;
      result.put(new Cell(e.getKey().x,iy,e.getKey().z),maskBoxes(e.getValue(),resolution,b0,b1));
    }
    if(result.size()>RoadLimits.MAX_BODY_CELLS)throw new IllegalArgumentException("道路实体占位过大，请增加中间端点");
    return result;
  }
  private static double[] rectangle(V a,V b,V c,V d,int resolution){
    if(a.y()!=b.y()||a.y()!=c.y()||a.y()!=d.y())return null;
    if(!((a.x()==b.x()&&b.z()==c.z()&&c.x()==d.x()&&d.z()==a.z())
        ||(a.z()==b.z()&&b.x()==c.x()&&c.z()==d.z()&&d.x()==a.x())))return null;
    double x0=Math.min(a.x(),c.x()),x1=Math.max(a.x(),c.x());
    double z0=Math.min(a.z(),c.z()),z1=Math.max(a.z(),c.z());
    if(x1-x0<1e-4||z1-z0<1e-4)return null;
    for(double v:new double[]{x0,x1,z0,z1}){
      double f=v*resolution-Math.floor(v*resolution);
      // Retain old area clipping for slivers near an exact grid boundary.
      if(f!=0&&Math.min(f,1-f)<1e-4)return null;
    }
    return new double[]{x0,z0,x1,z1};
  }
  private static List<Box> maskBoxes(long mask,int resolution,double low,double high){
    long full=resolution==8?-1L:(1L<<(resolution*resolution))-1;
    if(mask==full)return List.of(new Box(0,low,0,1,high,1));
    var rows=new ArrayList<Box>();double step=1.0/resolution;
    for(int z=0;z<resolution;z++)for(int x=0;x<resolution;){
      if((mask&(1L<<(z*resolution+x)))==0){x++;continue;}
      int begin=x++;while(x<resolution&&(mask&(1L<<(z*resolution+x)))!=0)x++;
      rows.add(new Box(begin*step,low,z*step,x*step,high,(z+1)*step));
    }
    return compact(rows);
  }

  private static Map<Cell, List<Box>> collect(
      Map<Tile, List<Interval>> tiles, int resolution, Cell only) {
    double step = 1.0 / resolution;
    Map<Cell, List<Box>> result = new HashMap<>();
    for (var entry : tiles.entrySet()) {
      RoadPlanningBudget.check();
      Tile tile = entry.getKey();
      double x0 = tile.x * step, z0 = tile.z * step;
      int x = Math.floorDiv(tile.x, resolution), z = Math.floorDiv(tile.z, resolution);
      for (Interval v : entry.getValue()) {
        int minY = (int) Math.floor(v.low), maxY = (int) Math.ceil(v.high - 1e-8) - 1;
        for (int y = minY; y <= maxY; y++) {
          if (only != null && y != only.y()) continue;
          Box box =
              new Box(
                  x0 - x,
                  Math.max(0, v.low - y),
                  z0 - z,
                  x0 + step - x,
                  Math.min(1, v.high - y),
                  z0 + step - z);
          if (box.y1 - box.y0 > 1e-8)
            result.computeIfAbsent(new Cell(x, y, z), k -> new ArrayList<>()).add(box);
        }
      }
    }
    if (result.size() > RoadLimits.MAX_BODY_CELLS)
      throw new IllegalArgumentException("道路实体占位过大，请增加中间端点");
    result.replaceAll((cell, boxes) -> compact(boxes));
    return result;
  }

  private static void triangle(
      Map<Tile, List<Interval>> tiles, V a, V b, V c, double thickness, int resolution, Cell only) {
    triangle(tiles,a,b,c,thickness,null,resolution,only);
  }
  private static void triangle(Map<Tile,List<Interval>> tiles,V a,V b,V c,double thickness,List<V> bottom,int resolution,Cell only) {
    double twiceArea=Math.abs((b.x()-a.x())*(c.z()-a.z())-(b.z()-a.z())*(c.x()-a.x()));
    double edge=Math.max(a.sub(b).horizontalLength(),Math.max(a.sub(c).horizontalLength(),b.sub(c).horizontalLength()));
    if(twiceArea<=edge*1e-7)return; // no collider for a numerical line left by a boolean cut
    double step = 1.0 / resolution;
    int x0 = (int) Math.floor(Math.min(a.x(), Math.min(b.x(), c.x())) * resolution),
        x1 = (int) Math.ceil(Math.max(a.x(), Math.max(b.x(), c.x())) * resolution) - 1;
    int z0 = (int) Math.floor(Math.min(a.z(), Math.min(b.z(), c.z())) * resolution),
        z1 = (int) Math.ceil(Math.max(a.z(), Math.max(b.z(), c.z())) * resolution) - 1;
    if (only != null) {
      x0 = Math.max(x0, only.x() * resolution);
      x1 = Math.min(x1, (only.x() + 1) * resolution - 1);
      z0 = Math.max(z0, only.z() * resolution);
      z1 = Math.min(z1, (only.z() + 1) * resolution - 1);
    }
    if (x0 > x1 || z0 > z1) return;
    // A triangle clipped by a rectangle has at most seven vertices. Reuse two arrays per
    // triangle instead of allocating four lists and many vectors for every 1/8-block tile.
    double[] poly = new double[36], scratch = new double[36];
    for (int x = x0; x <= x1; x++)
      for (int z = z0; z <= z1; z++) {
        poly[0]=a.x();poly[1]=a.y();poly[2]=a.z();
        poly[3]=b.x();poly[4]=b.y();poly[5]=b.z();
        poly[6]=c.x();poly[7]=c.y();poly[8]=c.z();
        int n=clip(poly,9,scratch,0,x*step,true);
        n=clip(scratch,n,poly,0,(x+1)*step,false);
        n=clip(poly,n,scratch,2,z*step,true);
        n=clip(scratch,n,poly,2,(z+1)*step,false);
        if(n<9||area(poly,n)<1e-11)continue;
        double min=Double.MAX_VALUE,max=-Double.MAX_VALUE;
        for(int i=1;i<n;i+=3){min=Math.min(min,bottom==null?poly[i]-thickness:RoadTerrainMesh.height(bottom,new V(poly[i-1],poly[i],poly[i+1])));max=Math.max(max,poly[i]);}
        Interval next = new Interval(min, max);
        var list = tiles.computeIfAbsent(new Tile(x, z), k -> new ArrayList<>());
        for (var it = list.iterator(); it.hasNext(); ) {
          Interval old = it.next();
          if (next.low <= old.high + 1e-7 && next.high >= old.low - 1e-7) {
            next.low = Math.min(next.low, old.low);
            next.high = Math.max(next.high, old.high);
            it.remove();
          }
        }
        list.add(next);
      }
  }

  private static final Comparator<Box> ROW_ORDER = (a,b) -> {
    int c=Double.compare(a.z0,b.z0); if(c!=0)return c;
    c=Double.compare(a.z1,b.z1); if(c!=0)return c;
    c=Double.compare(a.x0,b.x0); return c!=0?c:Double.compare(a.x1,b.x1);
  };
  private static final Comparator<Box> COLUMN_ORDER = (a,b) -> {
    int c=Double.compare(a.x0,b.x0); if(c!=0)return c;
    c=Double.compare(a.x1,b.x1); if(c!=0)return c;
    c=Double.compare(a.z0,b.z0); return c!=0?c:Double.compare(a.z1,b.z1);
  };
  private static final Comparator<Box> BOX_ORDER = (a,b) -> {
    int c=Double.compare(a.y0,b.y0); if(c!=0)return c;
    c=Double.compare(a.y1,b.y1); return c!=0?c:ROW_ORDER.compare(a,b);
  };

  /** Merge flat tile runs before storing them; interior slabs normally become one box. */
  public static List<Box> compact(List<Box> input) {
    if(input.size()<2)return new ArrayList<>(input);
    var fast=Boolean.getBoolean("sr.raster.legacyCompact")?null:compactFlat(input);if(fast!=null)return fast;
    List<Box> out = new ArrayList<>(input);
    Comparator<Box> order = BOX_ORDER;
    out.sort(order);
    for (int axis = 0; axis < 2; axis++) {
      for (int i = 0; i < out.size(); i++)
        for (int j = i + 1; j < out.size(); ) {
          Box a = out.get(i), b = out.get(j);
          if (b.y0 - a.y0 >= 1e-9) break; // Sorted height ranges cannot merge.
          boolean height = Math.abs(a.y0 - b.y0) < 1e-9 && Math.abs(a.y1 - b.y1) < 1e-9;
          boolean merge =
              height
                  && (axis == 0
                      ? a.z0 == b.z0 && a.z1 == b.z1 && (a.x1 == b.x0 || b.x1 == a.x0)
                      : a.x0 == b.x0 && a.x1 == b.x1 && (a.z1 == b.z0 || b.z1 == a.z0));
          if (merge) {
            out.set(
                i,
                new Box(
                    Math.min(a.x0, b.x0),
                    a.y0,
                    Math.min(a.z0, b.z0),
                    Math.max(a.x1, b.x1),
                    a.y1,
                    Math.max(a.z1, b.z1)));
            out.remove(j);
            j = i + 1;
          } else j++;
        }
    }
    out.sort(order);
    return out;
  }

  /** Exact flat-slab fast path. Most main-road cells are 16/64 equal-height
   * tiles. The old all-pairs/restart merge made every such cell quadratic.
   * Sort contiguous rows, then columns. Irregular/overlapping or almost-equal
   * heights fall back to the original tolerance-sensitive algorithm. */
  private static List<Box> compactFlat(List<Box> input){
    if(input.size()<2)return new ArrayList<>(input);
    var first=input.get(0);
    for(var b:input)if(b.y0()!=first.y0()||b.y1()!=first.y1())return null;
    Comparator<Box> rows = ROW_ORDER;
    var sorted=new ArrayList<>(input);sorted.sort(rows);
    var horizontal=new ArrayList<Box>();Box current=sorted.get(0);
    for(int i=1;i<sorted.size();i++){
      var b=sorted.get(i);
      if(current.z0()==b.z0()&&current.z1()==b.z1()){
        if(b.x0()<current.x1())return null;
        if(b.x0()==current.x1()){current=new Box(current.x0(),current.y0(),current.z0(),b.x1(),current.y1(),current.z1());continue;}
      }
      horizontal.add(current);current=b;
    }
    horizontal.add(current);
    horizontal.sort(COLUMN_ORDER);
    var out=new ArrayList<Box>();current=horizontal.get(0);
    for(int i=1;i<horizontal.size();i++){
      var b=horizontal.get(i);
      if(current.x0()==b.x0()&&current.x1()==b.x1()){
        if(b.z0()<current.z1())return null;
        if(b.z0()==current.z1()){current=new Box(current.x0(),current.y0(),current.z0(),current.x1(),current.y1(),b.z1());continue;}
      }
      out.add(current);current=b;
    }
    out.add(current);out.sort(rows);return out;
  }

  public static Map<Cell, List<Box>> structures(List<RoadStructures.Part> parts, Cell only) {
    Map<Cell, List<Box>> out = new HashMap<>();
    for (var part : parts) {
      RoadPlanningBudget.check();
      if(RailbedFurniture.visualOnly(part))continue;
      if (only != null) {
        double w = part.halfExtent();
        if (only.x() + 1 <= Math.min(part.a().x(), part.b().x()) - w
            || only.x() >= Math.max(part.a().x(), part.b().x()) + w
            || only.z() + 1 <= Math.min(part.a().z(), part.b().z()) - w
            || only.z() >= Math.max(part.a().z(), part.b().z()) + w
            || only.y() + 1 <= Math.min(part.a().y(), part.b().y()) - part.verticalFrame()
            || only.y() >= Math.max(part.a().y(), part.b().y()) + part.height() + part.verticalFrame()) continue;
      }
      if (part.pier() && part.frameA()==null && part.frameB()==null && Math.abs(part.a().y()-part.b().y())<1e-9) {
        V a = part.a();
        double w = part.halfExtent();
        addBox(
            out,
            a.x() - w,
            a.y(),
            a.z() - w,
            a.x() + w,
            a.y() + part.height(),
            part.b().z() + w,
            only);
      } else if (!RoadSignals.signal(part) && !RoadPoleModel.support(part)) {
        var base=part.base();
        double[] rect=part.heights()!=null||Boolean.getBoolean("sr.raster.legacyRectangles")?null:rectangle(base.get(0),base.get(1),base.get(2),base.get(3),4);
        if(rect!=null){
          double topY=base.get(0).y()+part.height();
          flatStructure(out,rect,topY-part.height(),topY,only);
          continue;
        }
        var top = part.top();
        Map<Tile, List<Interval>> tiles = new HashMap<>();
        triangle(tiles, top.get(0), top.get(1), top.get(2), part.height(),part.heights()==null?null:List.of(base.get(0),base.get(1),base.get(2)), 4, only);
        triangle(tiles, top.get(0), top.get(2), top.get(3), part.height(),part.heights()==null?null:List.of(base.get(0),base.get(2),base.get(3)), 4, only);
        collect(tiles, 4, only).forEach((cell, boxes) ->
            out.computeIfAbsent(cell, k -> new ArrayList<>()).addAll(boxes));
      } else {
        // Authored CB meshes do not use the generic prism footprint.
        var vertices = part.faces().stream().flatMap(f -> f.points().stream()).toList();
        addBox(out, vertices.stream().mapToDouble(V::x).min().orElseThrow(),
            vertices.stream().mapToDouble(V::y).min().orElseThrow(),
            vertices.stream().mapToDouble(V::z).min().orElseThrow(),
            vertices.stream().mapToDouble(V::x).max().orElseThrow(),
            vertices.stream().mapToDouble(V::y).max().orElseThrow(),
            vertices.stream().mapToDouble(V::z).max().orElseThrow(), only);
      }
    }
    return out;
  }

  private static void flatStructure(Map<Cell,List<Box>> out,double[] r,double low,double high,Cell only){
    double x0=Math.floor(r[0]*4)/4,x1=Math.ceil(r[2]*4)/4,z0=Math.floor(r[1]*4)/4,z1=Math.ceil(r[3]*4)/4;
    int ax=(int)Math.floor(x0),bx=(int)Math.ceil(x1)-1,az=(int)Math.floor(z0),bz=(int)Math.ceil(z1)-1;
    int ay=(int)Math.floor(low),by=(int)Math.ceil(high-1e-8)-1;
    if(only!=null){ax=Math.max(ax,only.x);bx=Math.min(bx,only.x);az=Math.max(az,only.z);bz=Math.min(bz,only.z);ay=Math.max(ay,only.y);by=Math.min(by,only.y);}
    for(int x=ax;x<=bx;x++)for(int y=ay;y<=by;y++)for(int z=az;z<=bz;z++){
      double bottom=Math.max(0,low-y),top=Math.min(1,high-y);if(top-bottom<=1e-8)continue;
      out.computeIfAbsent(new Cell(x,y,z),c->new ArrayList<>()).add(new Box(Math.max(0,x0-x),bottom,Math.max(0,z0-z),Math.min(1,x1-x),top,Math.min(1,z1-z)));
    }
  }

  private static void addBox(
      Map<Cell, List<Box>> out,
      double x0,
      double y0,
      double z0,
      double x1,
      double y1,
      double z1,
      Cell only) {
    int minX = (int) Math.floor(x0),
        minY = (int) Math.floor(y0),
        minZ = (int) Math.floor(z0),
        maxX = (int) Math.ceil(x1) - 1,
        maxY = (int) Math.ceil(y1) - 1,
        maxZ = (int) Math.ceil(z1) - 1;
    if (only != null) {
      minX = Math.max(minX, only.x);
      maxX = Math.min(maxX, only.x);
      minY = Math.max(minY, only.y);
      maxY = Math.min(maxY, only.y);
      minZ = Math.max(minZ, only.z);
      maxZ = Math.min(maxZ, only.z);
    }
    for (int x = minX; x <= maxX; x++)
      for (int y = minY; y <= maxY; y++)
        for (int z = minZ; z <= maxZ; z++)
          out.computeIfAbsent(new Cell(x, y, z), k -> new ArrayList<>())
              .add(
                  new Box(
                      Math.max(0, x0 - x),
                      Math.max(0, y0 - y),
                      Math.max(0, z0 - z),
                      Math.min(1, x1 - x),
                      Math.min(1, y1 - y),
                      Math.min(1, z1 - z)));
  }

  private static int clip(double[] in,int length,double[] out,int axis,double edge,boolean above) {
    if(length==0)return 0;
    int previous=length-3,count=0;
    boolean pi=above?in[previous+axis]>=edge:in[previous+axis]<=edge;
    for(int p=0;p<length;p+=3){
      boolean inside=above?in[p+axis]>=edge:in[p+axis]<=edge;
      if(inside!=pi){
        double t=(edge-in[previous+axis])/(in[p+axis]-in[previous+axis]);
        for(int k=0;k<3;k++)out[count++]=in[previous+k]+(in[p+k]-in[previous+k])*t;
      }
      if(inside){out[count++]=in[p];out[count++]=in[p+1];out[count++]=in[p+2];}
      previous=p;pi=inside;
    }
    return count;
  }
  private static double area(double[] p,int length){
    double sum=0;
    for(int i=3;i<length-3;i+=3)sum+=(p[i]-p[0])*(p[i+5]-p[2])-(p[i+2]-p[2])*(p[i+3]-p[0]);
    return Math.abs(sum)*.5;
  }

  private RoadRaster() {}
}
