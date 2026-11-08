package com.sora.splineroads.core;

import com.sora.splineroads.core.StreetPlan.*;
import java.util.*;

/** Six deliberately different street grammars, clipped and checked as one connected graph. */
public final class StreetLayouts {
  private record Line(Point a,Point b,int rank){}
  private final Input input;private final Terrain terrain;private final Options options;
  private final SplittableRandom random;private final List<Line> lines=new ArrayList<>();
  private final List<Issue> issues=new ArrayList<>();private final List<Point> centers=new ArrayList<>();
  private final double cx,cz,cos,sin,reach,spacing;
  private StreetLayouts(Input input,Terrain terrain){
    this.input=input;this.terrain=terrain;options=input.options();random=new SplittableRandom(new SplittableRandom(options.seed()).nextLong());
    double minX=input.selection().stream().mapToDouble(c->c.x()*16).min().orElseThrow(),maxX=input.selection().stream().mapToDouble(c->c.x()*16+16).max().orElseThrow();
    double minZ=input.selection().stream().mapToDouble(c->c.z()*16).min().orElseThrow(),maxZ=input.selection().stream().mapToDouble(c->c.z()*16+16).max().orElseThrow();
    cx=input.centers().isEmpty()?(minX+maxX)/2:input.centers().get(0).x();cz=input.centers().isEmpty()?(minZ+maxZ)/2:input.centers().get(0).z();reach=Math.hypot(Math.max(cx-minX,maxX-cx),Math.max(cz-minZ,maxZ-cz));
    cos=Math.cos(Math.toRadians(options.direction()));sin=Math.sin(Math.toRadians(options.direction()));spacing=options.spacing();
  }
  public static Plan generate(Input input,Terrain terrain){return new StreetLayouts(input,terrain).generate();}
  private Point world(double x,double z){return snap(new Point(cx+x*cos-z*sin,cz+x*sin+z*cos));}
  private static Point snap(Point p){return new Point(Math.floor(p.x())+.5,Math.floor(p.z())+.5);}
  private Point anchor(Point p){for(var e:input.entries())if(e.point().distance(p)<(e.existing()?.8:2))return e.point();return snap(p);}
  private void line(Point a,Point b,int rank){if(a.distance(b)>8)lines.add(new Line(a,b,rank));}
  private void local(double x0,double z0,double x1,double z1,int rank){line(world(x0,z0),world(x1,z1),rank);}
  private double[] stations(double step,boolean varied){
    var values=new ArrayList<Double>();values.add(0.0);
    for(int sign:new int[]{-1,1}){double p=0;while(Math.abs(p)<reach){p+=sign*step*(varied?.78+random.nextDouble()*.48:1);values.add(p);}}
    return values.stream().sorted().mapToDouble(Double::doubleValue).toArray();
  }
  private Plan generate(){
    RoadPlanningBudget.check();
    switch(options.style()){
      case DOWNTOWN,NEW_TOWN->grid();
      case OLD_TOWN->oldTown();
      case TOWN->town();
      case SUBURB,RURAL->settlements();
    }
    if(centers.isEmpty())centers.addAll(input.centers().isEmpty()?List.of(world(0,0)):input.centers());
    reserveEntries();attachEntries();
    var clipped=new ArrayList<Line>();for(var line:lines){RoadPlanningBudget.check();clip(line,clipped);}
    return graph(clipped);
  }
  private void grid(){
    boolean newer=options.style()==Style.NEW_TOWN;double step=spacing*(newer?1.42:1)*(.96+random.nextDouble()*.08);
    double phase=(random.nextDouble()-.5)*step*.3;
    var xs=stations(step,false);var zs=stations(step*(newer?1.1:1),false);
    for(int i=0;i<xs.length;i++){double x=xs[i];int rank=Math.abs(x)<.01?0:newer?(i%2==0?1:2):i%3==0?0:2;double at=x==0?0:x+phase;local(at,-reach,at,reach,rank);}
    for(int i=0;i<zs.length;i++){double z=zs[i];int rank=Math.abs(z)<.01?0:newer?1:i%3==0?1:2;double at=z==0?0:z+phase;local(-reach,at,reach,at,rank);}
  }
  private void oldTown(){
    double upper=Math.tan(Math.toRadians(10+random.nextDouble()*9)),lower=-Math.tan(Math.toRadians(7+random.nextDouble()*8));
    var xs=stations(spacing*1.08,true);var zs=stations(spacing,true);
    for(double x:xs){int rank=Math.abs(x)<.01?0:2;local(x+lower*-reach,-reach,x,0,rank);local(x,0,x+upper*reach,reach,rank);}
    for(double z:zs){double shear=z<0?lower:upper;local(-reach+shear*z,z,reach+shear*z,z,Math.abs(z)<.01?0:1);}
  }
  private void town(){
    double s=spacing*(.96+random.nextDouble()*.08),shift=0;
    local(-reach,shift,reach,shift,0);
    for(int side:new int[]{-1,1}){
      local(-2.35*s,side*s+shift,2.35*s,side*s+shift,2);
      local(-1.15*s,side*2.3*s+shift,1.15*s,side*2.3*s+shift,2);
    }
    for(double x:new double[]{-2.35,-1.15,0,1.15,2.35}){double end=Math.abs(x)>2?1:2.3;local(x*s,-end*s+shift,x*s,end*s+shift,x==0?1:2);}
    centers.addAll(input.centers().isEmpty()?List.of(world(0,shift)):input.centers());
  }
  private void settlements(){
    boolean rural=options.style()==Style.RURAL;double s=Math.max(68,spacing*(rural?.82:1));
    if(!input.centers().isEmpty())centers.addAll(input.centers());
    else{
      double separation=rural?1.55:1.25;Point left=world(-separation*s,0),right=world(separation*s,0);
      if(reach>=(rural?3.6:2.9)*s&&input.allowed(left.x(),left.z())&&input.allowed(right.x(),right.z())){centers.add(left);centers.add(right);}
      else {centers.add(world(0,0));issues.add(new Issue(world(0,0),"当前选区按单个"+(rural?"村落":"片区")+"组织；扩大区域可增加中心",false));}
    }
    var sizes=new ArrayList<Double>();for(int index=0;index<centers.size();index++){
      var center=centers.get(index);double angle=Math.toRadians(options.direction());
      double c=Math.cos(angle),sn=Math.sin(angle),step=s*(rural?1:index%2==0?.84:.78)*(.96+random.nextDouble()*.08);sizes.add(step);
      for(int axis=0;axis<2;axis++)for(int k=-1;k<=1;k++){
        double p=k*step,extent=step;
        Point a=offset(center,axis==0?p:-extent,axis==0?-extent:p,c,sn),b=offset(center,axis==0?p:extent,axis==0?extent:p,c,sn);
        line(a,b,k==0?1:2);
      }
    }
    // A deterministic minimum spanning tree links concentrated settlements without filling the voids.
    var connected=new HashSet<Integer>();connected.add(0);var usedPorts=new ArrayList<Set<Integer>>();for(var p:centers)usedPorts.add(new HashSet<>());
    while(connected.size()<centers.size()){
      int from=-1,to=-1;double best=Double.POSITIVE_INFINITY;
      for(int a:connected)if(usedPorts.get(a).size()<4)for(int b=0;b<centers.size();b++)if(!connected.contains(b)){double d=centers.get(a).distance(centers.get(b));if(d<best){best=d;from=a;to=b;}}
      Point a=settlementPort(from,to,sizes,usedPorts),b=settlementPort(to,from,sizes,usedPorts);line(a,b,0);connected.add(to);
    }
    if(!rural&&centers.size()>1){
      var a=centers.get(0);var b=centers.get(centers.size()-1);
      Point outerA=offset(a,0,s*1.75,cos,sin),outerB=offset(b,0,s*1.75,cos,sin);
      line(a,outerA,1);line(outerA,outerB,1);line(outerB,b,1);
    }
  }
  private Point settlementPort(int from,int to,List<Double> sizes,List<Set<Integer>> used){
    Point origin=centers.get(from),target=centers.get(to);int selected=-1;double best=Double.POSITIVE_INFINITY;
    for(int side=0;side<4;side++)if(!used.get(from).contains(side)){Point p=offset(origin,side<2?(side==0?1:-1)*sizes.get(from):0,side>=2?(side==2?1:-1)*sizes.get(from):0,cos,sin);double d=p.distance(target);if(d<best){best=d;selected=side;}}
    if(selected<0)throw new IllegalArgumentException("中心连接方向过多，请调整片区中心");used.get(from).add(selected);return offset(origin,selected<2?(selected==0?1:-1)*sizes.get(from):0,selected>=2?(selected==2?1:-1)*sizes.get(from):0,cos,sin);
  }
  private static Point offset(Point p,double x,double z,double cos,double sin){return snap(new Point(p.x()+x*cos-z*sin,p.z()+x*sin+z*cos));}
  private void reserveEntries(){
    // Keep a usable approach to each fixed interface. Nearby cross streets would
    // otherwise leave a three-block stub that cannot hold a native junction.
    lines.removeIf(line->{double length=line.a.distance(line.b);for(var e:input.entries()){
      double r=Math.toRadians(e.yaw()),dot=Math.abs((line.b.x()-line.a.x())*-Math.sin(r)+(line.b.z()-line.a.z())*Math.cos(r))/length;
      if(dot<.95&&project(e.point(),line).distance(e.point())<options.primaryWidth()+38)return true;
    }return false;});
  }
  private void attachEntries(){
    var skeleton=List.copyOf(lines);
    for(var entry:input.entries()){
      RoadPlanningBudget.check();Point start=entry.point();double r=Math.toRadians(entry.yaw());
      if(!entry.existing()&&skeleton.stream().anyMatch(l->project(start,l).distance(start)<2))continue;
      Point lead=entry.existing()?snap(new Point(start.x()-Math.sin(r)*spacing,start.z()+Math.cos(r)*spacing)):start;
      Point best=null;double score=Double.POSITIVE_INFINITY;
      for(var line:skeleton){var p=project(lead,line);double d=p.distance(lead),distance=start.distance(p);
        double forward=(p.x()-start.x())*-Math.sin(r)+(p.z()-start.z())*Math.cos(r);
        if(d<score&&distance>=32&&forward>=distance*.35&&input.allowed(p.x(),p.z())&&terrain.at(p.x(),p.z()).ground()==Ground.LAND){score=d;best=p;}}
      if(best==null){issues.add(new Issue(start,"接口附近没有可连接的骨架道路",true));continue;}
      if(entry.existing())line(start,lead,0);
      if(lead.distance(best)>2)line(lead,best,0);
      else if(!entry.existing())issues.add(new Issue(start,"接口与骨架重合，将使用现有规划节点",false));
    }
  }
  private static Point project(Point p,Line line){double dx=line.b.x()-line.a.x(),dz=line.b.z()-line.a.z();double t=((p.x()-line.a.x())*dx+(p.z()-line.a.z())*dz)/(dx*dx+dz*dz);return snap(line.a.mix(line.b,Math.max(0,Math.min(1,t))));}
  private boolean allowed(Point p,double half){
    for(int axis=0;axis<8;axis++){double angle=axis*Math.PI/4;double x=p.x()+Math.cos(angle)*half,z=p.z()+Math.sin(angle)*half;if(!input.allowed(x,z)||terrain.at(x,z).ground()!=Ground.LAND)return false;}
    return input.allowed(p.x(),p.z())&&terrain.at(p.x(),p.z()).ground()==Ground.LAND;
  }
  private void clip(Line source,List<Line> output){
    int steps=Math.max(1,(int)Math.ceil(source.a.distance(source.b)/2));int start=-1;boolean cut=false;
    for(int i=0;i<=steps;i++){
      if((i&31)==0)RoadPlanningBudget.check();Point p=source.a.mix(source.b,(double)i/steps);
      boolean valid=allowed(p,options.fullWidth(source.rank)/2);
      if(valid&&start<0)start=i;
      if(start>=0&&(!valid||i==steps)){int end=valid?i:i-1;Point a=anchor(source.a.mix(source.b,(double)start/steps)),b=anchor(source.a.mix(source.b,(double)end/steps));if(a.distance(b)>=44)output.add(new Line(a,b,source.rank));start=-1;}
      if(!valid&&input.allowed(p.x(),p.z())&&terrain.at(p.x(),p.z()).ground()!=Ground.LAND)cut=true;
    }
    if(cut)issues.add(new Issue(source.a.mix(source.b,.5),"道路避开水体、建筑障碍或未读取区域；被截断的片区将检查可达性",false));
  }
  private Plan graph(List<Line> source){
    var cuts=new ArrayList<TreeSet<Double>>();for(var line:source){var values=new TreeSet<Double>();values.add(0.0);values.add(1.0);cuts.add(values);}
    for(int i=0;i<source.size();i++)for(var entry:input.entries())if(project(entry.point(),source.get(i)).distance(entry.point())<(entry.existing()?.5:2))cutAt(entry.point(),source.get(i),cuts.get(i));
    for(int i=0;i<source.size();i++)for(int j=i+1;j<source.size();j++){RoadPlanningBudget.check();intersect(source.get(i),source.get(j),cuts.get(i),cuts.get(j));}
    var points=new LinkedHashMap<Point,Integer>();var edges=new LinkedHashMap<Long,Edge>();
    for(int i=0;i<source.size();i++){
      var line=source.get(i);Double previous=null;
      for(double t:cuts.get(i)){if(previous!=null){Point a=anchor(line.a.mix(line.b,previous)),b=anchor(line.a.mix(line.b,t));if(a.distance(b)>.75){int ai=points.computeIfAbsent(a,p->points.size()),bi=points.computeIfAbsent(b,p->points.size());if(ai!=bi){long key=edgeKey(ai,bi);var old=edges.get(key);edges.put(key,new Edge(ai,bi,old==null?line.rank:Math.min(old.rank(),line.rank)));}}}previous=t;}
    }
    var vertices=new ArrayList<Vertex>();for(var entry:points.entrySet()){double y=terrain.at(entry.getKey().x(),entry.getKey().z()).y();for(var in:input.entries())if(in.point().distance(entry.getKey())<1&&in.existing())y=in.height();vertices.add(new Vertex(entry.getValue(),entry.getKey(),y));}
    var result=new ArrayList<>(edges.values());mergeStraight(vertices,result);
    result.removeIf(e->{Vertex a=vertices.get(e.a()),b=vertices.get(e.b());String error=terrainError(a,b,e.rank());if(error!=null){issues.add(new Issue(a.point().mix(b.point(),.5),error,false));return true;}return false;});
    // Reject unusably short junction spacing before handing a graph to native junction geometry.
    boolean changed;do{changed=false;var degree=degree(vertices.size(),result);for(var it=result.iterator();it.hasNext();){var e=it.next();double length=vertices.get(e.a()).point().distance(vertices.get(e.b()).point());double minimum=degree[e.a()]>=3&&degree[e.b()]>=3?options.width(e.rank())*2+42:degree[e.a()]>=3||degree[e.b()]>=3?options.width(e.rank())+24:12;if(length<minimum){issues.add(new Issue(vertices.get(e.a()).point(),"路口间距不足，已减少短支路",false));it.remove();changed=true;}}if(changed)mergeStraight(vertices,result);}while(changed);
    pruneUnjoinedOverlaps(vertices,result);
    retainConnected(vertices,result);
    for(int i=0;i<result.size();i++){
      var e=result.get(i);var a=vertices.get(e.a());var b=vertices.get(e.b());double bend=StreetCurves.proposed(input,a,b,e.rank());
      if(bend!=0&&terrainError(a,b,e.rank(),bend)==null&&curveClear(vertices,result,e,bend))result.set(i,new Edge(e.a(),e.b(),e.rank(),bend));
    }
    var degree=degree(vertices.size(),result);
    for(var v:vertices){if(degree[v.id()]>4)issues.add(new Issue(v.point(),"该连接超过首版三四向路口能力，请调整中心或接口",true));if(degree[v.id()]>=2){var headings=new ArrayList<Double>();for(var e:result)if(e.a()==v.id()||e.b()==v.id()){var p=vertices.get(e.a()==v.id()?e.b():e.a()).point();headings.add(Math.atan2(p.z()-v.point().z(),p.x()-v.point().x()));}Collections.sort(headings);for(int i=0;i<headings.size();i++){double gap=headings.get((i+1)%headings.size())-headings.get(i);if(gap<=0)gap+=Math.PI*2;if(gap<Math.toRadians(25))issues.add(new Issue(v.point(),"接入口夹角不足25°，请调整中心或接口",true));}}}
    for(var entry:input.entries())if(vertices.stream().noneMatch(v->degree[v.id()]>0&&v.point().distance(entry.point())<1))issues.add(new Issue(entry.point(),"指定接口未接通；请调整位置、走向或明确连接走廊",true));
    if(result.size()>StreetPlan.MAX_EDGES)issues.add(new Issue(world(0,0),"道路数量超过首版上限，请降低密度或缩小选区",true));
    if(result.isEmpty())issues.add(new Issue(world(0,0),"选区过小、过窄或受阻，无法容纳合法道路和路口",true));
    return new Plan(input,vertices,result,centers,issues.stream().distinct().sorted(Comparator.comparing(Issue::fatal).reversed()).limit(128).toList());
  }
  private String terrainError(Vertex a,Vertex b,int rank){
    return terrainError(a,b,rank,0);
  }
  private String terrainError(Vertex a,Vertex b,int rank,double bend){
    double length=a.point().distance(b.point());if(!Double.isFinite(a.height())||!Double.isFinite(b.height()))return "地形尚未读取";
    if(1.5*Math.abs(a.height()-b.height())/length>.08)return "道路纵坡超过首版8%缓坡范围，未自动生成桥隧";
    int count=Math.max(1,(int)Math.ceil(length/2));
    for(int i=0;i<=count;i++){Point p=StreetCurves.point(a.point(),b.point(),bend,(double)i/count);if(!allowed(p,options.fullWidth(rank)/2))return "完整道路占地进入保护区、选区外或障碍";double t=(double)i/count;double y=a.height()+(b.height()-a.height())*(t*t*(3-2*t));for(int axis=0;axis<8;axis++){double angle=axis*Math.PI/4,half=options.fullWidth(rank)/2;double ground=terrain.at(p.x()+Math.cos(angle)*half,p.z()+Math.sin(angle)*half).y();if(Math.abs(ground-y)>options.earthwork()+.5)return "完整路幅地形起伏超过挖填预算，需调整道路或保护范围";}if(Math.abs(terrain.at(p.x(),p.z()).y()-y)>options.earthwork()+.5)return "地形起伏超过挖填预算，需调整道路或保护范围";}
    return null;
  }
  private static double distance(Point p,Point a,Point b){double dx=b.x()-a.x(),dz=b.z()-a.z();double t=((p.x()-a.x())*dx+(p.z()-a.z())*dz)/(dx*dx+dz*dz);return p.distance(a.mix(b,Math.max(0,Math.min(1,t))));}
  private boolean shared(Edge a,Edge b){return a.a()==b.a()||a.a()==b.b()||a.b()==b.a()||a.b()==b.b();}
  private boolean curveClear(List<Vertex> vertices,List<Edge> edges,Edge own,double bend){
    var a=vertices.get(own.a()).point();var b=vertices.get(own.b()).point();
    for(var other:edges)if(other!=own&&!shared(own,other)){
      var c=vertices.get(other.a()).point();var d=vertices.get(other.b()).point();double gap=(options.fullWidth(own.rank())+options.fullWidth(other.rank()))/2+1;
      double pad=gap+Math.abs(bend)+Math.abs(other.bend());
      if(Math.min(a.x(),b.x())>Math.max(c.x(),d.x())+pad||Math.max(a.x(),b.x())<Math.min(c.x(),d.x())-pad||Math.min(a.z(),b.z())>Math.max(c.z(),d.z())+pad||Math.max(a.z(),b.z())<Math.min(c.z(),d.z())-pad)continue;
      int n=Math.max(1,(int)Math.ceil(a.distance(b)/2)),m=Math.max(1,(int)Math.ceil(c.distance(d)/8));
      for(int i=0;i<=n;i++){Point p=StreetCurves.point(a,b,bend,(double)i/n);for(int j=0;j<m;j++)if(distance(p,StreetCurves.point(c,d,other.bend(),(double)j/m),StreetCurves.point(c,d,other.bend(),(double)(j+1)/m))<gap)return false;}
    }return true;
  }
  private void pruneUnjoinedOverlaps(List<Vertex> vertices,List<Edge> edges){
    // Clipping at an irregular selection boundary can leave a spur only a few
    // blocks short of another centerline. Its pavement still overlaps although
    // the graph has no junction. Never submit that phantom disconnected mouth.
    boolean changed;do{changed=false;var degrees=degree(vertices.size(),edges);
      outer:for(int i=0;i<edges.size();i++)for(int j=i+1;j<edges.size();j++){
        var a=edges.get(i);var b=edges.get(j);if(shared(a,b))continue;
        var ap=vertices.get(a.a()).point();var aq=vertices.get(a.b()).point();var bp=vertices.get(b.a()).point();var bq=vertices.get(b.b()).point();
        double gap=(options.fullWidth(a.rank())+options.fullWidth(b.rank()))/2+1;
        if(Math.min(Math.min(distance(ap,bp,bq),distance(aq,bp,bq)),Math.min(distance(bp,ap,aq),distance(bq,ap,aq)))>=gap)continue;
        boolean spurA=degrees[a.a()]==1||degrees[a.b()]==1,spurB=degrees[b.a()]==1||degrees[b.b()]==1;
        Edge remove=spurA!=spurB?(spurA?a:b):a.rank()!=b.rank()?(a.rank()>b.rank()?a:b):ap.distance(aq)<bp.distance(bq)?a:b;
        edges.remove(remove);issues.add(new Issue(vertices.get(remove.a()).point(),"边界短支路无法形成完整路口，已留出道路间距",false));changed=true;break outer;
      }
    }while(changed);
  }
  private void retainConnected(List<Vertex> vertices,List<Edge> edges){
    var adjacency=new ArrayList<List<Integer>>();for(var v:vertices)adjacency.add(new ArrayList<>());for(var e:edges){adjacency.get(e.a()).add(e.b());adjacency.get(e.b()).add(e.a());}
    var seen=new HashSet<Integer>();List<Integer> largest=List.of();
    for(var v:vertices)if(!adjacency.get(v.id()).isEmpty()&&!seen.contains(v.id())){var component=new ArrayList<Integer>();var queue=new ArrayDeque<Integer>();queue.add(v.id());while(!queue.isEmpty()){int id=queue.remove();if(!seen.add(id))continue;component.add(id);queue.addAll(adjacency.get(id));}if(component.size()>largest.size())largest=component;}
    var keep=new HashSet<>(largest);boolean removed=edges.removeIf(e->!keep.contains(e.a()));if(removed)issues.add(new Issue(world(0,0),"部分受阻片区无法接入主体路网，已标为留白；可调整保护区或指定接口后重算",false));
  }
  private static int[] degree(int count,List<Edge> edges){var result=new int[count];for(var e:edges){result[e.a()]++;result[e.b()]++;}return result;}
  private void mergeStraight(List<Vertex> vertices,List<Edge> edges){
    boolean changed;do{changed=false;var degree=degree(vertices.size(),edges);for(var v:vertices)if(degree[v.id()]==2&&input.entries().stream().noneMatch(e->e.point().distance(v.point())<1)){
      var adjacent=edges.stream().filter(e->e.a()==v.id()||e.b()==v.id()).toList();Edge x=adjacent.get(0),y=adjacent.get(1);int a=x.a()==v.id()?x.b():x.a(),b=y.a()==v.id()?y.b():y.a();var p=vertices.get(a).point();var q=vertices.get(b).point();double dot=((p.x()-v.point().x())*(q.x()-v.point().x())+(p.z()-v.point().z())*(q.z()-v.point().z()))/(p.distance(v.point())*q.distance(v.point()));
      if(dot<-.999&&x.rank()==y.rank()){edges.remove(x);edges.remove(y);if(a!=b)edges.add(new Edge(a,b,x.rank()));changed=true;break;}
    }}while(changed);
  }
  private static long edgeKey(int a,int b){return (long)Math.min(a,b)<<32^(Math.max(a,b)&0xffffffffL);}
  private static double cross(double ax,double az,double bx,double bz){return ax*bz-az*bx;}
  private static void intersect(Line a,Line b,Set<Double> ca,Set<Double> cb){
    double ax=a.b.x()-a.a.x(),az=a.b.z()-a.a.z(),bx=b.b.x()-b.a.x(),bz=b.b.z()-b.a.z();double dx=b.a.x()-a.a.x(),dz=b.a.z()-a.a.z(),det=cross(ax,az,bx,bz);
    if(Math.abs(det)<1e-7){if(Math.abs(cross(dx,dz,ax,az))<1e-4){for(var p:List.of(b.a,b.b))cutAt(p,a,ca);for(var p:List.of(a.a,a.b))cutAt(p,b,cb);}return;}
    double ta=cross(dx,dz,bx,bz)/det,tb=cross(dx,dz,ax,az)/det;if(ta>=0&&ta<=1&&tb>=0&&tb<=1){ca.add(ta);cb.add(tb);}
  }
  private static void cutAt(Point p,Line line,Set<Double> cuts){double dx=line.b.x()-line.a.x(),dz=line.b.z()-line.a.z(),t=((p.x()-line.a.x())*dx+(p.z()-line.a.z())*dz)/(dx*dx+dz*dz);if(t>0&&t<1)cuts.add(t);}
}
