package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Immutable inputs and outputs for local street-network planning; no world access. */
public final class StreetPlan {
  public static final int VERSION=1,MAX_CHUNKS=1024,MAX_EDGES=160,MAX_CENTERS=8,MAX_ENTRIES=8;
  public enum Style {
    DOWNTOWN("中心城区"),OLD_TOWN("老城区"),NEW_TOWN("新城区"),SUBURB("郊区"),RURAL("农村"),TOWN("小镇");
    public final String label;Style(String label){this.label=label;}
    public Style next(){return values()[(ordinal()+1)%values().length];}
  }
  public enum Ground { LAND,WATER,OBSTACLE,UNKNOWN,ROAD }
  public record Cell(int x,int z) implements Comparable<Cell> {
    public long key(){return (long)x<<32^(z&0xffffffffL);}
    public static Cell from(long value){return new Cell((int)(value>>32),(int)value);}
    public static Cell at(double x,double z){return new Cell(Math.floorDiv((int)Math.floor(x),16),Math.floorDiv((int)Math.floor(z),16));}
    public int compareTo(Cell other){int c=Integer.compare(x,other.x);return c!=0?c:Integer.compare(z,other.z);}
  }
  public record Surface(double y,Ground ground){}
  @FunctionalInterface public interface Terrain { Surface at(double x,double z); }
  public record Point(double x,double z){
    public Point {if(!RoadGeometry.finite(x,z)||Math.abs(x)>29_000_000||Math.abs(z)>29_000_000)throw new IllegalArgumentException("规划位置超出可用世界范围");}
    public double distance(Point other){return Math.hypot(x-other.x,z-other.z);}
    public Point mix(Point other,double t){return new Point(x+(other.x-x)*t,z+(other.z-z)*t);}
  }
  /** An existing endpoint has a fixed height/direction and an authoritative reference key. */
  public record Entry(Point point,double height,double yaw,boolean left,boolean existing,String reference){
    public Entry {Objects.requireNonNull(point);Objects.requireNonNull(reference);if(!RoadGeometry.finite(height,yaw)||height< -2048||height>4096||reference.length()>128)throw new IllegalArgumentException("对外接口数据无效");}
  }
  public record Options(Style style,double density,double blockSize,double direction,long seed,
                        double primaryWidth,double secondaryWidth,double localWidth,
                        RoadGeometry.Style primarySection,RoadGeometry.Style secondarySection,RoadGeometry.Style localSection,boolean left,
                        boolean sidewalks,boolean lighting,double earthwork){
    public Options {
      Objects.requireNonNull(style);
      for(var section:List.of(primarySection,secondarySection,localSection))if(RoadProfile.catalog(section).type()!=RoadProfile.Type.ORDINARY||!RoadProfile.catalog(section).twoWay())throw new IllegalArgumentException("自动路网首版仅支持普通双向道路断面");
      if(!RoadGeometry.finite(density,blockSize,direction,primaryWidth,secondaryWidth,localWidth,earthwork)
          ||density<.1||density>1||blockSize<24||blockSize>192||Math.abs(direction)>360
          ||localWidth<8||secondaryWidth<8||primaryWidth<8||primaryWidth>24||secondaryWidth>24||localWidth>24
          ||earthwork<0||earthwork>3)throw new IllegalArgumentException("路网密度、街坊、断面或地形预算超出首版范围");
    }
    public static Options defaults(){return new Options(Style.DOWNTOWN,.65,48,0,1,18,12,9,RoadGeometry.Style.O4_YELLOW,RoadGeometry.Style.O2_YELLOW,RoadGeometry.Style.O2_YELLOW,false,true,true,1);}
    public RoadGeometry.Style section(int rank){return rank==0?primarySection:rank==1?secondarySection:localSection;}
    public double width(int rank){return rank==0?primaryWidth:rank==1?secondaryWidth:localWidth;}
    public double fullWidth(int rank){return width(rank)+(sidewalks?4:0)+4;}
    public double spacing(){return Math.max(2*Math.max(primaryWidth,Math.max(secondaryWidth,localWidth))+64,blockSize+fullWidth(0))*(1.5-.5*density);}
  }
  public record Input(Set<Cell> selection,Set<Cell> protection,List<Point> centers,List<Entry> entries,Options options,Set<Cell> corridor,boolean corridorConfirmed){
    public Input(Set<Cell> selection,Set<Cell> protection,List<Point> centers,List<Entry> entries,Options options){this(selection,protection,centers,entries,options,Set.of(),false);}
    public Input {
      Objects.requireNonNull(options);selection=ordered(selection);protection=ordered(protection);centers=List.copyOf(centers);entries=List.copyOf(entries);corridor=ordered(corridor);
      if(selection.isEmpty()||selection.size()>MAX_CHUNKS||protection.size()>MAX_CHUNKS||centers.size()>MAX_CENTERS||entries.size()>MAX_ENTRIES)throw new IllegalArgumentException("规划选区、中心或接口数量超限");
      var all=new HashSet<>(selection);all.addAll(corridor);if(all.size()>MAX_CHUNKS)throw new IllegalArgumentException("选区与连接走廊总面积超限");
      for(var c:corridor)if(Math.abs((long)c.x())>1_800_000||Math.abs((long)c.z())>1_800_000||selection.stream().noneMatch(p->Math.abs((long)c.x()-p.x())<=8&&Math.abs((long)c.z()-p.z())<=8))throw new IllegalArgumentException("连接走廊须在选区周边8区块以内");
      if(!selection.containsAll(protection))throw new IllegalArgumentException("保护区必须位于本次选区内");
      int minX=selection.stream().mapToInt(Cell::x).min().orElseThrow(),maxX=selection.stream().mapToInt(Cell::x).max().orElseThrow();
      int minZ=selection.stream().mapToInt(Cell::z).min().orElseThrow(),maxZ=selection.stream().mapToInt(Cell::z).max().orElseThrow();
      if((long)maxX-minX>63||(long)maxZ-minZ>63||Math.abs((long)minX)>1_800_000||Math.abs((long)minZ)>1_800_000||Math.abs((long)maxX)>1_800_000||Math.abs((long)maxZ)>1_800_000)throw new IllegalArgumentException("首版规划边长最多64区块，且必须位于世界边界内");
      var reached=new HashSet<Cell>();var queue=new ArrayDeque<Cell>();queue.add(selection.iterator().next());
      while(!queue.isEmpty()){var c=queue.remove();if(!selection.contains(c)||!reached.add(c))continue;queue.add(new Cell(c.x+1,c.z));queue.add(new Cell(c.x-1,c.z));queue.add(new Cell(c.x,c.z+1));queue.add(new Cell(c.x,c.z-1));}
      if(reached.size()!=selection.size())throw new IllegalArgumentException("一次只能规划一个连续选区；请分开创建任务");
      for(var c:centers)if(!selection.contains(Cell.at(c.x,c.z))||protection.contains(Cell.at(c.x,c.z)))throw new IllegalArgumentException("规划中心必须位于可建设选区内");
      Boolean traffic=null;for(var e:entries)if(e.existing){if(traffic!=null&&traffic!=e.left)throw new IllegalArgumentException("已有入口的行驶方向冲突，请先统一后重试");traffic=e.left;}
      if(traffic!=null&&traffic!=options.left)throw new IllegalArgumentException("规划行驶方向必须与已有入口一致");
    }
    public Set<Cell> captureArea(){var area=new TreeSet<>(selection);area.addAll(corridor);return Collections.unmodifiableSet(area);}
    public boolean allowed(double x,double z){var c=Cell.at(x,z);return (selection.contains(c)||corridorConfirmed&&corridor.contains(c))&&!protection.contains(c);}
  }
  private static Set<Cell> ordered(Set<Cell> cells){return Collections.unmodifiableSet(new LinkedHashSet<>(new TreeSet<>(cells)));}
  public record Vertex(int id,Point point,double height){}
  public record Edge(int a,int b,int rank,double bend){
    public Edge(int a,int b,int rank){this(a,b,rank,0);}
    public Edge {if(a==b||rank<0||rank>2||!Double.isFinite(bend))throw new IllegalArgumentException("道路连接无效");}
  }
  public record Issue(Point point,String message,boolean fatal){}
  public record Plan(Input input,List<Vertex> vertices,List<Edge> edges,List<Point> centers,List<Issue> issues){
    public Plan {vertices=List.copyOf(vertices);edges=List.copyOf(edges);centers=List.copyOf(centers);issues=List.copyOf(issues);}
    public boolean buildable(){return !edges.isEmpty()&&issues.stream().noneMatch(Issue::fatal);}
    public int junctions(){var counts=new int[vertices.size()];for(var e:edges){counts[e.a]++;counts[e.b]++;}return (int)Arrays.stream(counts).filter(n->n>=3).count();}
  }
  private StreetPlan(){}
}
