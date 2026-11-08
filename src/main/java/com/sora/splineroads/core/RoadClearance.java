package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Exact piecewise-planar deck overlaps. A centerline projection is not a collision location. */
public final class RoadClearance {
  public static final double REQUIRED = 4.0;
  public static String clearanceLabel(double clearance){
    return clearance<0?String.format(Locale.ROOT,"路面实体重叠 %.2f 格（可用净空 0.00）",-clearance):String.format(Locale.ROOT,"板底净空 %.2f 格",clearance);
  }
  private static final double EPS = 1e-8, AREA_EPS = 1e-4;
  /** Signed bounds retain the free clearance interval; raise/lower remain nonnegative diagnostic distances. */
  public record Contact(double from,double to,V ours,V other,double usableClearance,
                        double raise,double lower,double required,double raiseBound,double lowerBound) {
    public Contact(double from,double to,V ours,V other,double usableClearance,double raise,double lower,double required){this(from,to,ours,other,usableClearance,raise,lower,required,raise,lower);}
    public Contact(double from,double to,V ours,V other,double usableClearance,double raise,double lower){this(from,to,ours,other,usableClearance,raise,lower,REQUIRED);}
    public boolean blocked(){return usableClearance < required - .025;}
  }
  /** Full overlap witnesses for generated shared mouths. Both station intervals and
   * the affine height range come from every clipped polygon vertex, not its center. */
  public record DetailedContact(Contact contact,double otherFrom,double otherTo,
                                double minimumDifference,double maximumDifference) {
    public boolean coplanar(){return Math.abs(minimumDifference)<=1e-7&&Math.abs(maximumDifference)<=1e-7;}
  }
  public static final class Conflict extends IllegalArgumentException {
    private final Contact contact;
    public Conflict(Contact c){
      super(String.format(Locale.ROOT,
        "道路投影交叠于 X=%.2f Z=%.2f；新路 Y=%.2f、既有路 Y=%.2f，%s / 要求净空 %.2f 格",
        c.ours().x(),c.ours().z(),c.ours().y(),c.other().y(),clearanceLabel(c.usableClearance()),c.required()));
      contact=c;
    }
    public Contact contact(){return contact;}
  }
  private static final class Triangle {
    final V a,b,c;final double sa,sb,sc,minStation,maxStation;
    final double dx,dz,ex,ez,det,minX,maxX,minZ,maxZ;
    final List<V> polygon;final int hash;
    Triangle(V a,V b,V c,double sa,double sb,double sc){
      this.a=a;this.b=b;this.c=c;this.sa=sa;this.sb=sb;this.sc=sc;
      minStation=Math.min(sa,Math.min(sb,sc));maxStation=Math.max(sa,Math.max(sb,sc));
      dx=b.x()-a.x();dz=b.z()-a.z();ex=c.x()-a.x();ez=c.z()-a.z();det=dx*ez-dz*ex;
      minX=Math.min(a.x(),Math.min(b.x(),c.x()));maxX=Math.max(a.x(),Math.max(b.x(),c.x()));
      minZ=Math.min(a.z(),Math.min(b.z(),c.z()));maxZ=Math.max(a.z(),Math.max(b.z(),c.z()));
      polygon=det>=0?List.of(a,b,c):List.of(a,c,b);hash=Objects.hash(a,b,c,sa,sb,sc);
    }
    double det(){return det;}
    double value(V p,double va,double vb,double vc){return value(p.x(),p.z(),va,vb,vc);}
    double value(double x,double z,double va,double vb,double vc){
      double qx=x-a.x(),qz=z-a.z();
      return va+(qx*ez-qz*ex)/det*(vb-va)+(dx*qz-dz*qx)/det*(vc-va);
    }
    double height(V p){return value(p,a.y(),b.y(),c.y());}
    double station(V p){return value(p,sa,sb,sc);}
    double height(double x,double z){return value(x,z,a.y(),b.y(),c.y());}
    double station(double x,double z){return value(x,z,sa,sb,sc);}
    List<V> polygon(){return polygon;}
    double minX(){return minX;} double maxX(){return maxX;}
    double minZ(){return minZ;} double maxZ(){return maxZ;}
    @Override public int hashCode(){return hash;}
    @Override public boolean equals(Object o){return this==o||o instanceof Triangle t
        &&a.equals(t.a)&&b.equals(t.b)&&c.equals(t.c)&&Double.compare(sa,t.sa)==0&&Double.compare(sb,t.sb)==0&&Double.compare(sc,t.sc)==0;}
  }
  /** Query-local marks replace an allocated hash set for every tested triangle.
   * Canonical IDs preserve the old value-equality de-duplication and visit order.
   * Scratch belongs to the calling thread; no mutable state is shared by workers. */
  private static final class NearScratch {
    int[] seen=new int[0]; int stamp; final ArrayList<Triangle> result=new ArrayList<>();
    void begin(int size){if(seen.length<size)seen=new int[size];if(++stamp==0){Arrays.fill(seen,0);stamp=1;}result.clear();}
  }
  private static final ThreadLocal<NearScratch> NEAR=ThreadLocal.withInitial(NearScratch::new);
  private static final class Grid {
    private record Indexed(Triangle triangle,int id){}
    final Map<Long,List<Indexed>> cells=new HashMap<>(); final int size;
    Grid(Mesh mesh){this(mesh,false);}
    Grid(Mesh mesh,boolean driving){
      Map<Triangle,Indexed> unique=new HashMap<>();
      for(Triangle t:driving?makeTriangles(mesh,true):triangles(mesh)){
        Indexed indexed=unique.get(t);
        if(indexed==null){indexed=new Indexed(t,unique.size());unique.put(t,indexed);}
        for(int x=cell(t.minX());x<=cell(t.maxX());x++)for(int z=cell(t.minZ());z<=cell(t.maxZ());z++)
          cells.computeIfAbsent(key(x,z),k->new ArrayList<>()).add(indexed);
      }
      size=unique.size();
    }
    // Borrowed until the next near() call on this thread; all consumers finish
    // iterating synchronously and do not recursively query another grid.
    List<Triangle> near(Triangle t){return nearAfter(t,Double.NEGATIVE_INFINITY);}
    List<Triangle> nearAfter(Triangle t,double station){var scratch=NEAR.get();scratch.begin(size);
      for(int x=cell(t.minX());x<=cell(t.maxX());x++)for(int z=cell(t.minZ());z<=cell(t.maxZ());z++){
        var bucket=cells.getOrDefault(key(x,z),List.of());int first=0;
        // makeTriangles appends segments in station order, including every lane
        // strip. Binary search skips only provably adjacent/earlier triangles.
        if(Double.isFinite(station)){
          int end=bucket.size();while(first<end){int mid=(first+end)>>>1;
            if(bucket.get(mid).triangle().maxStation<=station)first=mid+1;else end=mid;}
        }
        for(int i=first;i<bucket.size();i++){Indexed indexed=bucket.get(i);
          Triangle q=indexed.triangle();
          if(q.maxX()>t.minX()+EPS&&q.minX()<t.maxX()-EPS&&q.maxZ()>t.minZ()+EPS&&q.minZ()<t.maxZ()-EPS&&scratch.seen[indexed.id()]!=scratch.stamp){
            scratch.seen[indexed.id()]=scratch.stamp;scratch.result.add(q);
          }
        }
      }
      return scratch.result;
    }
  }
  // Grid values contain triangle vectors, never the Mesh key. Identity keys are weak and bounded;
  // a newly generated/deformed mesh therefore cannot reuse stale collision geometry.
  private static final WeakIdentityCache<Mesh,Grid> GRIDS=new WeakIdentityCache<>(256,500_000,
      grid->grid.cells.values().stream().mapToInt(List::size).sum());
  private static Grid grid(Mesh mesh){return GRIDS.get(mesh,Grid::new);}
  private static final WeakIdentityCache<Mesh,Grid> DRIVING_GRIDS=new WeakIdentityCache<>(256,500_000,
      grid->grid.cells.values().stream().mapToInt(List::size).sum());
  private static Grid drivingGrid(Mesh mesh){return LaneDeck.hasOpenings(mesh)?DRIVING_GRIDS.get(mesh,m->new Grid(m,true)):grid(mesh);}
  public static void clearPreparedCache(){GRIDS.clear();DRIVING_GRIDS.clear();TRIANGLES.clear();}
  public static WeakIdentityCache.Stats preparedCacheStats(){return GRIDS.stats();}
  /** Immutable prepared deck index; callers may retain it only for their current planning query. */
  public static final class Prepared {
    private final Mesh mesh;private final Grid grid;
    private Prepared(Mesh mesh){this.mesh=mesh;this.grid=grid(mesh);}
  }
  public static Prepared prepare(Mesh mesh){return new Prepared(mesh);}
  public static List<Contact> contacts(Mesh a,Prepared other){return contacts(a,other.mesh,null,other.grid);}
  public static List<Contact> contacts(Prepared ours,Mesh b){
    Mesh a=ours.mesh;
    if(a.max().x()<=b.min().x()+EPS||b.max().x()<=a.min().x()+EPS||a.max().z()<=b.min().z()+EPS||b.max().z()<=a.min().z()+EPS)return List.of();
    var out=new ArrayList<Contact>();
    for(Triangle q:triangles(b)){RoadPlanningBudget.check();for(Triangle t:ours.grid.near(q))appendContact(out,a,b,t,q);}
    return List.copyOf(out);
  }
  public static void check(Mesh a,Mesh b){check(a,b,null);}
  public static void check(Mesh a,Mesh b,Mesh previous){
    for(Contact c:contacts(a,b,previous))if(c.blocked())throw new Conflict(c);
  }
  /** All geometric crossings, including currently-clear ones, for explicit OVER/UNDER planning. */
  public static List<Contact> contacts(Mesh a,Mesh b){return contacts(a,b,null);}
  public static List<DetailedContact> detailedContacts(Mesh a,Mesh b){
    if(a.max().x()<=b.min().x()+EPS||b.max().x()<=a.min().x()+EPS||a.max().z()<=b.min().z()+EPS||b.max().z()<=a.min().z()+EPS)return List.of();
    var out=new ArrayList<DetailedContact>();var index=grid(b);
    for(Triangle t:triangles(a)){
      RoadPlanningBudget.check();
      for(Triangle q:index.near(t))appendContact(null,out,a,b,t,q);
    }
    return List.copyOf(out);
  }
  /** Nonlocal parts of one ribbon, excluding adjacent triangles before clipping. */
  public static List<DetailedContact> detailedSelfContacts(Mesh mesh,double stationGap){
    var out=new ArrayList<DetailedContact>();var index=grid(mesh);
    for(var a:triangles(mesh)){
      RoadPlanningBudget.check();
      for(var b:index.nearAfter(a,a.minStation+stationGap)){
        // Conservative broad phase: the clipped contact, not triangle bounds,
        // decides the final station gap at the caller.
        appendContact(null,out,mesh,mesh,a,b);
      }
    }
    return List.copyOf(out);
  }
  public static List<Contact> contacts(Mesh a,Mesh b,Mesh previous){
    if(a.max().x()<=b.min().x()+EPS||b.max().x()<=a.min().x()+EPS||a.max().z()<=b.min().z()+EPS||b.max().z()<=a.min().z()+EPS)return List.of();
    return contacts(a,b,previous,grid(b));
  }
  private static List<Contact> contacts(Mesh a,Mesh b,Mesh previous,Grid grid){
    var out=new ArrayList<Contact>();
    for(Triangle t:triangles(a)){
      RoadPlanningBudget.check();
      // Preserve only already-built, unchanged material during a non-ramp road edit.
      if(previous!=null&&t.polygon().stream().allMatch(v->RoadQueries.contains(previous,v,.015,.02)))continue;
      for(Triangle q:grid.near(t)){
        appendContact(out,a,b,t,q);
      }
    }
    return List.copyOf(out);
  }
  private static double solidDepth(Mesh mesh){return mesh.settings().thickness()+RoadInfrastructure.girderDepth(mesh);}
  private static void appendContact(List<Contact> out,Mesh a,Mesh b,Triangle t,Triangle q){
    appendContact(out,null,a,b,t,q);
  }
  private static void appendContact(List<Contact> out,List<DetailedContact> detailed,Mesh a,Mesh b,Triangle t,Triangle q){
    var clipped=CLIP.get();int n=clipped.intersection(t.polygon(),q.polygon());
    if(clipped.area(n)<AREA_EPS)return;
    double[] polygon=clipped.first;
    double from=Double.POSITIVE_INFINITY,to=Double.NEGATIVE_INFINITY,min=Double.POSITIVE_INFINITY;
    double otherFrom=Double.POSITIVE_INFINITY,otherTo=Double.NEGATIVE_INFINITY;
    double raise=Double.NEGATIVE_INFINITY,lower=Double.NEGATIVE_INFINITY,minDiff=Double.POSITIVE_INFINITY,maxDiff=Double.NEGATIVE_INFINITY;
    double required=RoadInfrastructure.girderDepth(a)>0||RoadInfrastructure.girderDepth(b)>0||fascia(a)||fascia(b)?4.25:REQUIRED;
    V ours=null,other=null;int negative=0,positive=0;
    for(int i=0;i<n;i+=3){
      double x=polygon[i],z=polygon[i+2];double ya=t.height(x,z),yb=q.height(x,z),difference=ya-yb;
      if((difference<=0&&a.settings().style()==Style.RAILBED)||(difference>=0&&b.settings().style()==Style.RAILBED))required=Math.max(required,RailbedGeometry.HEADROOM);
      double gap=Math.abs(difference)-(difference>=0?solidDepth(a):solidDepth(b));
      if(gap<min){min=gap;ours=new V(x,ya,z);other=new V(x,yb,z);}
      double station=t.station(x,z);from=Math.min(from,station);to=Math.max(to,station);
      if(detailed!=null||RailwayCrossings.pair(a,b)){double otherStation=q.station(x,z);otherFrom=Math.min(otherFrom,otherStation);otherTo=Math.max(otherTo,otherStation);}
      if(difference<minDiff){minDiff=difference;negative=i;}if(difference>maxDiff){maxDiff=difference;positive=i;}
      raise=Math.max(raise,yb+required+solidDepth(a)+.10-ya);
      lower=Math.max(lower,ya+required+solidDepth(b)+.10-yb);
    }
    if(minDiff<0&&maxDiff>0){
      double alpha=-minDiff/(maxDiff-minDiff);
      double x=polygon[negative]+(polygon[positive]-polygon[negative])*alpha;
      double z=polygon[negative+2]+(polygon[positive+2]-polygon[negative+2])*alpha;
      ours=new V(x,t.height(x,z),z);other=new V(x,q.height(x,z),z);
      min=-Math.min(solidDepth(a),solidDepth(b));
    }
    var contact=new Contact(from,to,ours,other,min,Math.max(0,raise),Math.max(0,lower),required,raise,lower);
    if(out!=null&&!RailwayCrossings.allows(a,b,from,to,otherFrom,otherTo,minDiff,maxDiff))out.add(contact);
    if(detailed!=null)detailed.add(new DetailedContact(contact,otherFrom,otherTo,minDiff,maxDiff));
  }
  /** At most six vertices for triangle/triangle intersection, with extra room
   * for duplicate boundary vertices. Same clipping arithmetic as the list path. */
  private static final class ClipScratch {
    double[] first=new double[48],second=new double[48];
    int intersection(List<V> subject,List<V> clip){
      int n=0;for(var v:subject){first[n++]=v.x();first[n++]=v.y();first[n++]=v.z();}
      for(int i=0;i<clip.size()&&n>0;i++){
        V a=clip.get(i),b=clip.get((i+1)%clip.size());double dx=b.x()-a.x(),dz=b.z()-a.z();
        int prev=n-3,count=0;double dp=dx*(first[prev+2]-a.z())-dz*(first[prev]-a.x());
        for(int cur=0;cur<n;cur+=3){
          double dc=dx*(first[cur+2]-a.z())-dz*(first[cur]-a.x());boolean pin=dp>=-EPS,cin=dc>=-EPS;
          if(pin!=cin){double den=dp-dc;if(Math.abs(den)>EPS){double alpha=dp/den;for(int k=0;k<3;k++)second[count++]=first[prev+k]+(first[cur+k]-first[prev+k])*alpha;}}
          if(cin){second[count++]=first[cur];second[count++]=first[cur+1];second[count++]=first[cur+2];}
          prev=cur;dp=dc;
        }
        double[] swap=first;first=second;second=swap;n=count;
      }
      return n;
    }
    double area(int n){if(n<9)return 0;double sum=0;for(int i=3;i<n-3;i+=3)
      sum+=(first[i]-first[0])*(first[i+5]-first[2])-(first[i+2]-first[2])*(first[i+3]-first[0]);return Math.abs(sum)/2;}
  }
  private static final ThreadLocal<ClipScratch> CLIP=ThreadLocal.withInitial(ClipScratch::new);
  /** Exact swept road travel-volume vs an actual framed structural prism.
   * The old shell check combined the entire part's vertical bounds with one midpoint
   * road elevation: a long sloped beam could be rejected where it never touches road.
   * Boundary-only contact is not an obstruction. LaneDeck preserves real cut slots. */
  public static boolean structureInvades(RoadStructures.Part part,Mesh road,double headroom){
    var base=part.base();var top=part.top();if(base.size()<3)return false;
    var index=drivingGrid(road);
    for(int i=1;i<base.size()-1;i++){
      var t=new Triangle(base.get(0),base.get(i),base.get(i+1),0,0,0);var roof=new Triangle(top.get(0),top.get(i),top.get(i+1),0,0,0);
      if(Math.abs(t.det())<EPS)continue;
      for(var q:index.near(t)){
        var polygon=intersection(t.polygon(),q.polygon());if(area(polygon)<AREA_EPS)continue;
        double min=Double.POSITIVE_INFINITY,max=Double.NEGATIVE_INFINITY;
        for(var point:polygon){double gap=t.height(point)-q.height(point);min=Math.min(min,gap);max=Math.max(max,roof.height(point)-q.height(point));}
        // The gap is affine over each clipped triangle. Its range intersects exactly
        // when the prism enters the live deck/travel interval, not just its XZ bounds.
        if(min<headroom-EPS&&max>-road.settings().thickness()+.04+EPS)return true;
      }
    }
    return false;
  }

  /** A joining pavement fill may share the volume below an existing road surface.
   * Check every clipped corner, so sloped or buried roads cannot acquire a lid. */
  public static boolean belowSurface(RoadStructures.Part part,Mesh road,double tolerance){
    var base=part.base();var top=part.top();var index=grid(road);
    for(int i=1;i<base.size()-1;i++){
      var t=new Triangle(base.get(0),base.get(i),base.get(i+1),0,0,0);var roof=new Triangle(top.get(0),top.get(i),top.get(i+1),0,0,0);if(Math.abs(t.det())<EPS)continue;
      for(var q:index.near(t)){
        var polygon=intersection(t.polygon(),q.polygon());if(area(polygon)<AREA_EPS)continue;
        for(var point:polygon)if(roof.height(point)>q.height(point)+tolerance)return false;
      }
    }return true;
  }

  /** A connected bridge fascia may share the joining deck's floor, never its air. */
  public static boolean joiningFloor(RoadStructures.Part part,Mesh owner,Mesh joining){
    return !part.pier()&&part.material()==RoadStructures.Material.CONCRETE
        &&part.height()<=owner.settings().thickness()+1e-7&&belowSurface(part,joining,.025);
  }

  /** Vertical alternatives against saved solids (rails, planter walls and piers).
   * Uses the same clipped triangles and framed heights as the final invasion test. */
  public static List<Contact> structureContacts(RoadStructures.Part part,Mesh road,double headroom){
    var out=new ArrayList<Contact>();var base=part.base();var top=part.top();if(base.size()<3)return out;
    var index=drivingGrid(road);
    for(int i=1;i<base.size()-1;i++){
      var t=new Triangle(base.get(0),base.get(i),base.get(i+1),0,0,0);var roof=new Triangle(top.get(0),top.get(i),top.get(i+1),0,0,0);if(Math.abs(t.det())<EPS)continue;
      for(var q:index.near(t)){
        var polygon=intersection(t.polygon(),q.polygon());if(area(polygon)<AREA_EPS)continue;
        double from=Double.POSITIVE_INFINITY,to=Double.NEGATIVE_INFINITY,raise=Double.NEGATIVE_INFINITY,lower=Double.NEGATIVE_INFINITY;
        double minGap=Double.POSITIVE_INFINITY,maxGap=Double.NEGATIVE_INFINITY;V ours=null,other=null;
        for(var p:polygon){double deck=q.height(p),bottom=t.height(p),gap=bottom-deck;
          from=Math.min(from,q.station(p));to=Math.max(to,q.station(p));minGap=Math.min(minGap,gap);maxGap=Math.max(maxGap,roof.height(p)-deck);
          raise=Math.max(raise,roof.height(p)+solidDepth(road)+.10-deck);
          lower=Math.max(lower,deck+headroom+.10-bottom);
          ours=new V(p.x(),deck,p.z());other=new V(p.x(),(bottom+roof.height(p))/2,p.z());
        }
        boolean blocked=minGap<headroom-EPS&&maxGap>-solidDepth(road)+.04+EPS;
        out.add(new Contact(from,to,ours,other,blocked?0:REQUIRED,Math.max(0,raise),Math.max(0,lower),REQUIRED,raise,lower));
      }
    }
    return out;
  }

  // The same immutable candidate is inspected by height solving, reopening and
  // final validation. Retain its exact tessellation, not just its destination grid.
  private static final WeakIdentityCache<Mesh,List<Triangle>> TRIANGLES=
      new WeakIdentityCache<>(256,500_000,List::size);
  private static List<Triangle> triangles(Mesh mesh){return TRIANGLES.get(mesh,RoadClearance::makeTriangles);}
  private static List<Triangle> makeTriangles(Mesh mesh){
    return makeTriangles(mesh,false);
  }
  private static boolean fascia(Mesh mesh){return mesh.settings().style().connectorRamp()
      &&mesh.settings().structure()!=Structure.GROUND&&mesh.settings().structure()!=Structure.TUNNEL;}
  private static List<Triangle> makeTriangles(Mesh mesh,boolean driving){
    var out=new ArrayList<Triangle>();var samples=mesh.samples();
    for(int i=1;i<samples.size();i++){
      if((i&63)==0)RoadPlanningBudget.check();
      var a=samples.get(i-1);var b=samples.get(i);
      for(var strip:driving?LaneDeck.drivingStrips(mesh,a,b):LaneDeck.strips(mesh,a,b)) {
        V al=strip.al(),ar=strip.ar(),bl=strip.bl(),br=strip.br();
        for(var t:List.of(new Triangle(al,ar,br,a.distance(),a.distance(),b.distance()),new Triangle(al,br,bl,a.distance(),b.distance(),b.distance())))
          if(Math.abs(t.det())>EPS)out.add(t);
      }
    }return List.copyOf(out);
  }
  private static List<V> intersection(List<V> subject,List<V> clip){
    List<V> polygon=subject;
    for(int i=0;i<clip.size()&&!polygon.isEmpty();i++){
      V a=clip.get(i),b=clip.get((i+1)%clip.size()),d=b.sub(a);var next=new ArrayList<V>();
      V prev=polygon.get(polygon.size()-1);double dp=cross(d,prev.sub(a));
      for(V cur:polygon){double dc=cross(d,cur.sub(a));boolean pin=dp>=-EPS,cin=dc>=-EPS;
        if(pin!=cin){double den=dp-dc;if(Math.abs(den)>EPS)next.add(prev.add(cur.sub(prev).mul(dp/den)));}
        if(cin)next.add(cur);prev=cur;dp=dc;
      }polygon=next;
    }return polygon;
  }
  private static double area(List<V> p){if(p.size()<3)return 0;double sum=0;V origin=p.get(0);for(int i=1;i<p.size()-1;i++)sum+=cross(p.get(i).sub(origin),p.get(i+1).sub(origin));return Math.abs(sum)/2;}
  private static double cross(V a,V b){return a.x()*b.z()-a.z()*b.x();}
  private static int cell(double x){return (int)Math.floor(x/16);}
  private static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
  private RoadClearance(){}
}
