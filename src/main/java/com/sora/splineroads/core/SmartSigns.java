package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;

/** Versioned semantic content. No generated words, distances, route names or abbreviations. */
public final class SmartSigns {
  public static final int VERSION=7, MAX_TEXT=80, MAX_DRAFTS=8, MAX_FIELDS=96;
  public enum Mode { NORMAL, EXIT_AUTO, IC_JCT, EMPTY, SPEED_LIMIT, ROADSIDE, EXIT_SIGN, JUNCTION_ADVANCE, ROAD_NAME, LANE_INDICATOR }
  public static boolean freestanding(Mode mode){return mode==Mode.ROADSIDE||mode==Mode.JUNCTION_ADVANCE||mode==Mode.ROAD_NAME;}
  public static final List<Mode> STYLES=List.of(Mode.EMPTY,Mode.EXIT_SIGN,Mode.SPEED_LIMIT,Mode.IC_JCT,Mode.LANE_INDICATOR);
  public static String styleLabel(Mode mode){return switch(mode){case EXIT_AUTO,EXIT_SIGN->"出口预告龙门架";case SPEED_LIMIT->"限速龙门架";case LANE_INDICATOR->"车道指示龙门架";case IC_JCT->"JCT/IC预告龙门架";default->"空龙门架";};}
  /** Persisted world-space mount, derived only from the owning fork's saved geometry. */
  public record Support(V foot,V right,V front,boolean paired,int cantileverSide,double gantrySpan,Double boardCenter){
    public Support(V foot,V right,V front,boolean paired,int cantileverSide,double gantrySpan){this(foot,right,front,paired,cantileverSide,gantrySpan,null);}
    public Support(V foot,V right,V front,boolean paired,int cantileverSide){this(foot,right,front,paired,cantileverSide,0);}
    public Support(V foot,V right,V front,boolean paired){this(foot,right,front,paired,0);}
    public Support {if(boardCenter!=null&&(!Double.isFinite(boardCenter)||Math.abs(boardCenter)>256))throw new IllegalArgumentException("无效的标牌中心位置");if(!Double.isFinite(gantrySpan)||Math.abs(gantrySpan)>256||cantileverSide< -1||cantileverSide>1||foot==null||right==null||front==null||!RoadGeometry.finite(foot.x(),foot.y(),foot.z(),right.x(),right.y(),right.z(),front.x(),front.y(),front.z())||Math.abs(right.horizontalLength()-1)>.001||Math.abs(front.horizontalLength()-1)>.001||Math.abs(right.dot(front))>.001||Math.abs(right.y())>.001||Math.abs(front.y())>.001)throw new IllegalArgumentException("无效的路杆安装位置");}
  }
  public record Key(UUID road,int slot,int flow){
    public Key {if(road==null||slot<0||slot>=RoadGantry.MAX_SLOTS||Math.abs(flow)!=1)throw new IllegalArgumentException("无效的龙门架标识");}
    public UUID id(){return UUID.nameUUIDFromBytes((road+":"+slot+":"+flow).getBytes(java.nio.charset.StandardCharsets.UTF_8));}
  }
  public record Text(String value,int size,boolean wrap){
    public static final Text EMPTY=new Text("",1,true);
    public Text {if(value==null||value.codePointCount(0,value.length())>MAX_TEXT||value.codePoints().anyMatch(c->(Character.isISOControl(c)&&c!='\n')||c==167)||size<0||size>2)throw new IllegalArgumentException("文字最多 80 字，除换行外不支持控制符；字号 0–2");}
  }
  public record Branch(String id,List<Integer> lanes,int side,boolean dedicated,List<V> path){
    public Branch {lanes=List.copyOf(lanes);path=List.copyOf(path);if(id==null||id.length()>100||lanes.size()>32||Math.abs(side)>1||path.size()>128)throw new IllegalArgumentException("无效的分支");}
  }
  public record State(Key key,Mode mode,UUID target,String diagnostic,long revision,List<Branch> branches,
      Map<String,Text> text,Map<String,Map<String,Text>> drafts,String entry,Support support,Set<String> hiddenHeaders,Set<String> hiddenAccessories,Set<String> closedLanes){
    public State(Key key,Mode mode,UUID target,String diagnostic,long revision,List<Branch> branches,Map<String,Text> text,Map<String,Map<String,Text>> drafts,String entry,Support support,Set<String> hiddenHeaders,Set<String> hiddenAccessories){this(key,mode,target,diagnostic,revision,branches,text,drafts,entry,support,hiddenHeaders,hiddenAccessories,Set.of());}
    public State(Key key,Mode mode,UUID target,String diagnostic,long revision,List<Branch> branches,Map<String,Text> text,Map<String,Map<String,Text>> drafts,String entry,Support support,Set<String> hiddenHeaders){this(key,mode,target,diagnostic,revision,branches,text,drafts,entry,support,hiddenHeaders,Set.of());}
    public State(Key key,Mode mode,UUID target,String diagnostic,long revision,List<Branch> branches,Map<String,Text> text,Map<String,Map<String,Text>> drafts,String entry,Support support){this(key,mode,target,diagnostic,revision,branches,text,drafts,entry,support,Set.of());}
    public State(Key key,Mode mode,UUID target,String diagnostic,long revision,List<Branch> branches,Map<String,Text> text,Map<String,Map<String,Text>> drafts,String entry){this(key,mode,target,diagnostic,revision,branches,text,drafts,entry,null);}
    public State(Key key,Mode mode,UUID target,String diagnostic,long revision,List<Branch> branches,Map<String,Text> text,Map<String,Map<String,Text>> drafts){this(key,mode,target,diagnostic,revision,branches,text,drafts,Integer.toString(key.flow()));}
    public State {closedLanes=Set.copyOf(closedLanes);if(closedLanes.size()>32||closedLanes.stream().anyMatch(v->!v.matches("lane:[0-9]{1,2}")))throw new IllegalArgumentException("车道指示状态无效");hiddenAccessories=Set.copyOf(hiddenAccessories);if(hiddenAccessories.size()>MAX_FIELDS||hiddenAccessories.stream().anyMatch(v->v==null||v.length()>112||!(v.endsWith("/exit")||v.endsWith("/distance"))))throw new IllegalArgumentException("附属牌显隐状态无效");hiddenHeaders=Set.copyOf(hiddenHeaders);if(hiddenHeaders.size()>MAX_FIELDS||hiddenHeaders.stream().anyMatch(v->v==null||v.length()>100))throw new IllegalArgumentException("道路标识区状态无效");branches=List.copyOf(branches);text=Map.copyOf(text);var copy=new LinkedHashMap<String,Map<String,Text>>();drafts.forEach((k,v)->copy.put(k,Map.copyOf(v)));drafts=Collections.unmodifiableMap(copy);
      if(entry==null||entry.length()>100||branches.size()>12||text.size()>MAX_FIELDS||drafts.size()>MAX_DRAFTS)throw new IllegalArgumentException("标牌内容超过上限");}
    public static State empty(Key k){return new State(k,Mode.NORMAL,null,"",0,List.of(),Map.of(),Map.of());}
    public String targetKey(){return target==null?"ordinary":target+":"+entry;}
    public State layout(Mode next,UUID goal,List<Branch> bs,String reason){return layout(next,goal,bs,reason,Integer.toString(key.flow()));}
    public State layout(Mode next,UUID goal,List<Branch> bs,String reason,String approach){
      if(mode==next&&Objects.equals(target,goal)&&entry.equals(approach)&&branches.equals(bs)&&diagnostic.equals(reason))return this;
      var history=new LinkedHashMap<>(drafts);var words=new HashMap<>(text);
      if(!Objects.equals(target,goal)||!entry.equals(approach)){if(!words.isEmpty())history.put(targetKey(),Map.copyOf(words));words.clear();}
      var valid=fieldIds(next,bs);var lost=new HashMap<String,Text>();words.entrySet().removeIf(e->{if(!valid.contains(e.getKey())){lost.put(e.getKey(),e.getValue());return true;}return false;});
      if(!lost.isEmpty()){var archived=new HashMap<>(history.getOrDefault(targetKey(),Map.of()));archived.putAll(lost);history.put(targetKey(),archived);}
      while(history.size()>MAX_DRAFTS)history.remove(history.keySet().iterator().next());
      return new State(key,next,goal,reason,revision+1,bs,words,history,approach,freestanding(next)?support:null,hiddenHeaders,hiddenAccessories,closedLanes);
    }
    public State supported(Support value){return Objects.equals(support,value)?this:new State(key,mode,target,diagnostic,revision+1,branches,text,drafts,entry,value,hiddenHeaders,hiddenAccessories,closedLanes);}
    public State edit(String field,Text value){
      if(!fieldIds(mode,branches).contains(field))throw new IllegalArgumentException("该文字区域已失效，请重新选择");
      var words=new HashMap<>(text);if(value.value().isEmpty())words.remove(field);else words.put(field,value);
      return new State(key,mode,target,diagnostic,revision+1,branches,words,drafts,entry,support,hiddenHeaders,hiddenAccessories,closedLanes);
    }
    public boolean headerVisible(String branch){return !hiddenHeaders.contains(branch);}
    public State header(String branch,boolean visible){
      if(!headerMode(mode)||branches.stream().noneMatch(b->b.id().equals(branch)))throw new IllegalArgumentException("当前分支没有道路编号及名称区");
      var hidden=new HashSet<>(hiddenHeaders);if(visible)hidden.remove(branch);else hidden.add(branch);
      return hidden.equals(hiddenHeaders)?this:new State(key,mode,target,diagnostic,revision+1,branches,text,drafts,entry,support,hidden,hiddenAccessories,closedLanes);
    }
    private String accessoryKey(String branch,String part){
      if(!part.equals("exit")&&!part.equals("distance"))throw new IllegalArgumentException("无效的附属牌类型");
      if(mode==Mode.IC_JCT&&!branches.isEmpty())return diagramHeaderField(this,part);
      return branch+"/"+part;
    }
    public boolean accessoryVisible(String branch,String part){return !hiddenAccessories.contains(accessoryKey(branch,part));}
    public State accessory(String branch,String part,boolean visible){
      if(!headerMode(mode)||branches.stream().noneMatch(b->b.id().equals(branch)))throw new IllegalArgumentException("当前主牌没有出口编号或距离附属牌");
      String id=accessoryKey(branch,part);var hidden=new HashSet<>(hiddenAccessories);if(visible)hidden.remove(id);else hidden.add(id);
      return hidden.equals(hiddenAccessories)?this:new State(key,mode,target,diagnostic,revision+1,branches,text,drafts,entry,support,hiddenHeaders,hidden,closedLanes);
    }
    public State indicators(Set<String> closed){return closedLanes.equals(closed)?this:new State(key,mode,target,diagnostic,revision+1,branches,text,drafts,entry,support,hiddenHeaders,hiddenAccessories,closed);}
    public State indicator(String field,boolean closed){
      if(mode!=Mode.LANE_INDICATOR||!field.endsWith("/status"))throw new IllegalArgumentException("该车道指示牌已失效，请重新选择");
      String lane=field.substring(0,field.length()-7);
      if(!lane.startsWith("lane:")||branches.stream().noneMatch(b->b.id().equals(lane)))throw new IllegalArgumentException("该车道已变化，请重新选择指示牌");
      var values=new HashSet<>(closedLanes);if(closed)values.add(lane);else values.remove(lane);return indicators(values);
    }
    public State rekey(Key value){return new State(value,mode,target,diagnostic,revision+1,branches,text,drafts,entry,support,hiddenHeaders,hiddenAccessories,closedLanes);}
    public State restore(){
      var saved=drafts.get(targetKey());if(saved==null)throw new IllegalArgumentException("当前目标没有可恢复的草稿");var words=new HashMap<>(text);saved.forEach((k,v)->{if(fieldIds(mode,branches).contains(k))words.putIfAbsent(k,v);});
      return new State(key,mode,target,diagnostic,revision+1,branches,words,drafts,entry,support,hiddenHeaders,hiddenAccessories,closedLanes);
    }
  }
  /** Map the downstream exit through an outer-lane count transition, preserving order. */
  public static List<Integer> exitLaneMapping(List<Integer> upstream,List<Integer> downstream,Set<Integer> exit,int side){
    if(upstream.isEmpty()||downstream.isEmpty())return List.of();var mapped=new LinkedHashSet<Integer>();
    for(int i=0;i<downstream.size();i++)if(exit.contains(downstream.get(i))){
      int rank=side<0?i:downstream.size()-1-i;
      int index=side<0?Math.min(rank,upstream.size()-1):Math.max(0,upstream.size()-1-rank);
      mapped.add(upstream.get(index));
    }return List.copyOf(mapped);
  }
  /** Physical left-to-right placement is independent of persistent field/list order. */
  public static List<Branch> roadsideOrder(State state){
    int exitSide=state.branches().stream().filter(b->!b.id().equals("through")).mapToInt(Branch::side).findFirst().orElse(1);
    return state.branches().stream().sorted(Comparator.comparingInt((Branch b)->b.id().equals("through")?-exitSide:b.side()).thenComparing(Branch::id)).toList();
  }
  public static boolean headerMode(Mode mode){return mode==Mode.EXIT_AUTO||mode==Mode.EXIT_SIGN||mode==Mode.ROADSIDE||mode==Mode.IC_JCT;}
  public static final List<String> FIELDS=List.of("main","sub","route","road","roadSub","exit","distance");
  public static Set<String> fieldIds(List<Branch> branches){var out=new LinkedHashSet<String>();for(var b:branches)for(String f:FIELDS)out.add(b.id()+"/"+f);return out;}
  public static final List<String> SPEED_FIELDS=List.of("vehicle","vehicleSub","maxSpeed","minSpeed");
  public static Set<String> fieldIds(Mode mode,List<Branch> branches){if(mode==Mode.LANE_INDICATOR)return Set.of();if(mode==Mode.JUNCTION_ADVANCE||mode==Mode.ROAD_NAME){var out=new LinkedHashSet<String>();for(var b:branches)if(mode!=Mode.JUNCTION_ADVANCE||!b.path().isEmpty())for(var f:List.of("main","sub"))out.add(b.id()+"/"+f);return out;}if(mode!=Mode.SPEED_LIMIT)return fieldIds(branches);var out=new LinkedHashSet<String>();for(var b:branches)if(!b.id().equals("emergency"))for(String f:SPEED_FIELDS)out.add(b.id()+"/"+f);return out;}
  public static String label(String id){if(id.equals("road/main"))return "当前道路名称";if(id.equals("road/sub"))return "道路名称副文字";if(id.equals("junction/main"))return "路口预告主文字";if(id.equals("junction/sub"))return "路口预告副文字";return switch(id.substring(id.lastIndexOf('/')+1)){case "vehicle"->"车型主文字";case "vehicleSub"->"车型副文字";case "maxSpeed"->"最高时速";case "minSpeed"->"最低时速";case "main"->"目的地主文字";case "sub"->"目的地副文字";case "route"->"道路编号";case "road"->"道路名称";case "roadSub"->"道路名称副文字";case "exit"->"出口号／立交名";default->"距离（含单位请手填）";};}
  public static final int GREEN=0x1C8A36,WHITE=0xFAFAEE,DARK_GREEN=0x002417,RED=0xDA242D,BLUE=0x1256B0;
  public static final double BACK=.10,FACE=.33,INK=.355,TEXT=FACE+.002;
  public record Field(String id,double x,double y,double width,double height,int lines,int color,String literal){
    public Field(String id,double x,double y,double width,double height,int lines,int color){this(id,x,y,width,height,lines,color,"");}
    public boolean editable(){return literal.isEmpty();}
    public Field(String id,double x,double y,double width,double height,int lines){this(id,x,y,width,height,lines,WHITE);}
  }
  public record Board(String branch,double x,double width,double height,double bottom){
    public Board(String branch,double x,double width,double height){this(branch,x,width,height,0);}
    public double top(){return bottom+height;}
  }
  public record Layout(V origin,V right,V front,List<Board> boards,List<Field> fields,List<RoadSurface.Face> faces){
    public V at(double x,double y,double depth){return origin.add(right.mul(x)).add(new V(0,y,0)).add(front.mul(depth));}
    public double top(){return boards.stream().mapToDouble(Board::top).max().orElse(0);}
  }
  /** One road-tangent transform is shared by the frame, mounting clamps, boards and text. */
  public static Layout layout(Mesh mesh,State state){
    if(state.mode()==Mode.JUNCTION_ADVANCE||state.mode()==Mode.ROAD_NAME)return junctionLayout(state);
    boolean roadside=state.mode()==Mode.ROADSIDE&&state.support()!=null;
    var st=roadside?null:RoadGantry.station(mesh,state.key().slot());V right=roadside?state.support().right():st.sample().left().mul(state.key().flow()),front=right.left();
    double frameFront=st!=null&&st.kind()==RoadInfrastructure.Gantry.FRAME?.5:.225;
    // 35 mm standoff with physical clamps, not a floating board pushed forward
    // to compensate for a depth-buffer error. Metal and board use real depth.
    double mount=frameFront+.035-BACK;
    double mainHeight=state.mode()==Mode.SPEED_LIMIT?2.65:3.4;
    V origin=roadside?state.support().foot().add(new V(0,5.2,0)):new V(st.sample().center().x(),
        state.mode()==Mode.IC_JCT?st.sample().center().y()+st.edit().clearance():RoadGantry.upperBeamCenterY(st)-mainHeight/2,
        st.sample().center().z()).add(front.mul(mount));
    var boards=new ArrayList<Board>();var fields=new ArrayList<Field>();var faces=new ArrayList<RoadSurface.Face>();
    if(state.target()==null||!state.diagnostic().isEmpty()||state.branches().isEmpty())return new Layout(origin,right,front,List.of(),List.of(),List.of());
    if(state.mode()==Mode.LANE_INDICATOR)return indicatorLayout(mesh,state,st,right,front,mount);
    if(state.mode()==Mode.SPEED_LIMIT)return speedLayout(mesh,state,st,origin,right,front,frameFront,mount);
    var live=roadside?List.<LanePoints.Lane>of():LaneSections.live(mesh,st.distance()).lanes().stream().filter(l->l.sign()==state.key().flow()).toList();
    double min=live.stream().mapToDouble(l->l.position().sub(st.sample().center()).dot(right)-l.width()/2).min().orElse(0),max=live.stream().mapToDouble(l->l.position().sub(st.sample().center()).dot(right)+l.width()/2).max().orElse(0);
    if(!roadside&&max-min<2.8)return new Layout(origin,right,front,List.of(),List.of(),List.of());
    boolean ic=state.mode()==Mode.IC_JCT;double total=max-min-.4,center=(min+max)/2;
    if(ic)return diagramLayout(state,st,origin,right,front,carriagewayCenter(mesh,st.sample(),state.key().flow(),center),frameFront,mount);
    else if(roadside){var ordered=roadsideOrder(state);for(int i=0;i<ordered.size();i++){
      var branch=ordered.get(i);
      int side=branch.id().equals("through")?-state.branches().get(state.branches().size()-1).side():branch.side();
      int count=ordered.size(),cantilever=state.support().cantileverSide();
      // A roadside backup extends inward from the outside rail. Exit arrows
      // retain their semantic side; the pole/boards share this physical layout.
      double x=cantilever!=0?(i-(count-1)/2.0)*4.7+cantilever*count*2.35:count==1?side*2.35:(i-(count-1)/2.0)*4.7;
      double width=4.15;
      double span=state.support().gantrySpan();
      if(span!=0){
        double spacing=Math.min(4.7,Math.abs(span)/count);
        width=Math.min(width,spacing-.25);
        x=span/2+(i-(count-1)/2.0)*spacing;
        if(count==1&&branch.id().equals("elevated"))x=state.support().boardCenter()!=null?state.support().boardCenter():Math.min(0,span)+width/2+.125;
      }
      boards.add(new Board(branch.id(),x,width,3.4));accessoryBoard(boards,state,branch.id(),x,Math.min(3.95,width-.15),3.53);
    }
    }else for(var branch:state.branches()){
      var lanes=live.stream().filter(l->branch.lanes().contains(l.index())).toList();if(lanes.isEmpty())continue;
      double lo=lanes.stream().mapToDouble(l->l.position().sub(st.sample().center()).dot(right)-l.width()/2).min().orElseThrow(),hi=lanes.stream().mapToDouble(l->l.position().sub(st.sample().center()).dot(right)+l.width()/2).max().orElseThrow();
      double width=Math.min(7.8,Math.max(1.2,hi-lo-.25)),x=(lo+hi)/2;
      boards.add(new Board(branch.id(),x,width,3.4));
      accessoryBoard(boards,state,branch.id(),x,Math.min(width-.2,5.4),3.53);
    }
    // Both directions at an elevated entrance are columns on one physical sign.
    List<Board> panels=boards;
    if(roadside&&state.branches().size()==2&&state.branches().stream().anyMatch(b->b.id().equals("elevated"))&&state.branches().stream().anyMatch(b->b.id().equals("surface"))){
      var columns=boards.stream().filter(b->!b.branch().endsWith("/header")).toList();
      double left=columns.stream().mapToDouble(b->b.x()-b.width()/2).min().orElseThrow();
      double rightEdge=columns.stream().mapToDouble(b->b.x()+b.width()/2).max().orElseThrow();
      panels=new ArrayList<>(boards.stream().filter(b->b.branch().endsWith("/header")).toList());
      panels.add(new Board("elevated-access",(left+rightEdge)/2,rightEdge-left,3.4));
    }
    var transform=new Layout(origin,right,front,panels,fields,faces);
    for(var board:panels){
      shell(faces,transform,board);
      rounded(faces,transform,board.x()-board.width()/2,board.bottom(),board.x()+board.width()/2,board.top(),.10,FACE,WHITE);
      rounded(faces,transform,board.x()-board.width()/2+.065,board.bottom()+.065,board.x()+board.width()/2-.065,board.top()-.065,.065,FACE+.006,GREEN);
      if(!board.branch().endsWith("/header")){if(roadside)sideMount(faces,transform,board);else mounting(faces,transform,board,st,frameFront,mount);}
    }
    if(roadside){
      if(state.support().gantrySpan()!=0)halfGantry(transform,state.support().gantrySpan()).forEach(p->faces.addAll(p.faces()));
      else pole(faces,transform,panels);
    }
    for(int i=0;i<state.branches().size();i++){
      var branch=state.branches().get(i);var board=ic?boards.get(0):boards.stream().filter(b->b.branch().equals(branch.id())).findFirst().orElse(null);if(board==null)continue;
      {
        boolean elevatedThrough=branch.id().equals("elevated")&&state.branches().size()==1;
        double w=board.width(),x=board.x(),side=elevatedThrough||branch.side()==-1?-1:1;
        double arrowWidth=Math.min(1.5,w*.30),textWidth=w-arrowWidth-.38,textX=x-side*(arrowWidth/2+.02);
        if(state.headerVisible(branch.id()))header(faces,fields,transform,branch.id(),textX,2.78,textWidth,.78);
        fields.add(new Field(branch.id()+"/main",textX,1.88,textWidth,.78,2));
        fields.add(new Field(branch.id()+"/sub",textX,1.11,textWidth,.45,2));
        var top=boards.stream().filter(b->b.branch().equals(branch.id()+"/header")).findFirst().orElse(null);
        if(top!=null){
          boolean exit=state.accessoryVisible(branch.id(),"exit"),distance=state.accessoryVisible(branch.id(),"distance");
          if(exit)fields.add(new Field(branch.id()+"/exit",top.x()-(distance?top.width()*.145:0),3.84,distance?top.width()*.65-.16:top.width()-.22,.40,2));
          if(distance)fields.add(new Field(branch.id()+"/distance",top.x()+(exit?top.width()*.335:0),3.84,exit?top.width()*.28-.12:top.width()-.22,.32,1));
        }
        double ax=x+side*(w/2-arrowWidth/2-.12),ay=.73;
        boolean through=elevatedThrough||branch.id().equals("through")||branch.id().equals("surface");
        if(through)boldArrow(faces,transform,ax,ay,ax,2.85,.17,INK);
        else {
          double reach=arrowWidth*.68;
          boldArrow(faces,transform,ax-side*reach*.45,ay,ax+side*reach*.45,2.47,.17,INK);
        }
        // Small registration bolts and folded aluminium return add readable
        // physical construction without textures or a separate entity per panel.
        for(double bx:new double[]{x-w/2+.16,x+w/2-.16})for(double by:new double[]{.16,3.24})quad(faces,transform,bx-.025,by-.025,bx+.025,by+.025,FACE+.009,0xCFDDD5);
      }
    }
    return new Layout(origin,right,front,List.copyOf(panels),List.copyOf(fields),List.copyOf(faces));
  }
  private static void accessoryBoard(List<Board> boards,State state,String branch,double x,double width,double bottom){
    boolean exit=state.accessoryVisible(branch,"exit"),distance=state.accessoryVisible(branch,"distance");
    if(exit||distance)boards.add(new Board(branch+"/header",x,exit&&distance?width:Math.max(1, width*(exit?.72:.34)),.62,bottom));
  }
  private static Board accessoryBoard(Layout t,Board main){return t.boards().stream().filter(b->b.branch().equals(main.branch()+"/header")).findFirst().orElse(null);}
  /** The nameplate has two readable faces sharing exactly the same saved fields. */
  public static List<Layout> views(Layout front,Mode mode){
    if(mode!=Mode.ROAD_NAME)return List.of(front);
    var back=new Layout(front.origin().add(front.front().mul(BACK+FACE-.008)),front.right().mul(-1),front.front().mul(-1),
        front.boards().stream().map(b->new Board(b.branch(),-b.x(),b.width(),b.height(),b.bottom())).toList(),
        front.fields().stream().map(f->new Field(f.id(),-f.x(),f.y(),f.width(),f.height(),f.lines(),f.color(),f.literal())).toList(),List.of());
    var faces=new ArrayList<RoadSurface.Face>();
    for(var f:front.faces())if(SmartSignFaces.front(f,front)){
      var points=f.points().stream().map(p->{V d=p.sub(front.origin());return back.at(-d.dot(front.right()),d.y(),d.dot(front.front()));}).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
      Collections.reverse(points);faces.add(new RoadSurface.Face(points,f.color()));
    }
    return List.of(front,new Layout(back.origin(),back.right(),back.front(),back.boards(),back.fields(),List.copyOf(faces)));
  }
  private static Layout junctionLayout(State state){
    var mount=state.support();if(mount==null)return new Layout(new V(0,0,0),new V(1,0,0),new V(0,0,1),List.of(),List.of(),List.of());
    boolean advance=state.mode()==Mode.JUNCTION_ADVANCE,ring=state.entry().startsWith("roundabout");
    double base=advance?5.2:2.1,width=advance?3:2.4,height=advance?2:.65;
    double x=advance?((mount.paired()^state.branches().stream().anyMatch(b->b.id().equals("__median__")))?1:-1)*(width/2+.32):0;
    V origin=mount.foot().add(new V(0,base,0));var boards=List.of(new Board(advance?"junction":"road",x,width,height));
    var fields=new ArrayList<Field>();var faces=new ArrayList<RoadSurface.Face>();var t=new Layout(origin,mount.right(),mount.front(),boards,fields,faces);
    if(state.target()==null||!state.diagnostic().isEmpty())return new Layout(origin,mount.right(),mount.front(),List.of(),List.of(),List.of());
    var b=boards.get(0);shell(faces,t,b);rounded(faces,t,x-width/2,0,x+width/2,height,.055,FACE,WHITE);
    rounded(faces,t,x-width/2+.035,.035,x+width/2-.035,height-.035,.035,FACE+.006,0x19477E);
    // Embed the nameplate mast in its bottom edge, centered through its thickness.
    tube(faces,t,0,-base,0,advance?1.55:.045,advance?.095:.065,advance?-.03:(BACK+FACE)/2,0xBDC7C9);
    box(faces,t,-.15,-base,.15,-base+.12,advance?-.17:(BACK+FACE)/2-.15,advance?.13:(BACK+FACE)/2+.15,0x859192);
    if(advance)for(double y:new double[]{.55,1.45}){
      tube(faces,t,Math.min(0,x-width/2+.14),y,Math.max(0,x+width/2-.14),y,.05,-.03,0xC9D0D0);
      for(double dx:new double[]{-.85,.85})box(faces,t,x+dx-.065,y-.10,x+dx+.065,y+.10,-.08,BACK+.02,0xA6B4B2);
    }
    if(!advance){
      quad(faces,t,x-width/2+.035,.035,x+width/2-.035,.19,INK,WHITE);
      fields.add(new Field("road/main",x,(.19+height-.035)/2,width-.16,.34,1));
      fields.add(new Field("road/sub",x,.12,width-.16,.12,1,0x15191C));
    }else{
      double cy=ring&&state.branches().stream().anyMatch(v->!v.path().isEmpty()&&v.path().get(v.path().size()-1).z()<-.15)?.82:.52,r=.25;
      stroke(faces,t,x,ring?cy-r-.24:cy-.28,x,ring?cy-r:cy,.065,INK,WHITE);
      if(ring){
        // Shared radial edges form an annulus; tangent rectangles leave visible cracks.
        var inner=new ArrayList<V>();var outer=new ArrayList<V>();
        for(int i=0;i<64;i++){double a=2*Math.PI*i/64;inner.add(t.at(x+(r-.03)*Math.cos(a),cy+(r-.03)*Math.sin(a),INK));outer.add(t.at(x+(r+.03)*Math.cos(a),cy+(r+.03)*Math.sin(a),INK));}
        for(int i=0;i<64;i++){int j=(i+1)%64;faces.add(new RoadSurface.Face(List.of(inner.get(i),outer.get(i),outer.get(j),inner.get(j)),WHITE));}
      }
      var ordered=new ArrayList<>(state.branches().stream().filter(v->!v.path().isEmpty()).toList());
      ordered.sort(Comparator.comparingDouble(v->v.path().get(v.path().size()-1).x()));
      int leftCount=(int)ordered.stream().filter(v->v.side()<0).count(),rightCount=(int)ordered.stream().filter(v->v.side()>0).count(),li=0,ri=0;
      for(var branch:ordered){var d=branch.path().get(branch.path().size()-1);double dx=d.x(),dy=d.z(),length=Math.hypot(dx,dy);if(length<.01)continue;dx/=length;dy/=length;
        // Topology retains real tangents; ordinary signs use orthogonal symbols.
        if(!ring){if(branch.side()==0){dx=0;dy=1;}else{dx=branch.side();dy=0;}}
        double sx=x+dx*(ring?r:0),sy=cy+dy*(ring?r:0),reach=Math.min(1.24/Math.max(.001,Math.abs(dx)),(dy>=0?Math.min(.83,1.40-cy):cy-.12)/Math.max(.001,Math.abs(dy))),tx=x+dx*reach,ty=cy+dy*reach;
        continuousArrow(faces,t,List.of(new V(sx,sy,0),new V(tx,ty,0)),.065,INK+.01);
        double fx,fy,fw;
        if(branch.side()==0){fx=x;fy=1.75;fw=1.22;}
        else {int count=branch.side()<0?leftCount:rightCount,index=branch.side()<0?li++:ri++;fx=x+branch.side()*1.04;fy=count==1?(ring&&cy>.8&&dy>.15?.46:Math.max(1.05,cy+.58)):1.68-index*(1.2/Math.max(1,count-1));fw=.78;}
        fields.add(new Field(branch.id()+"/main",fx,fy,fw,.19,1));fields.add(new Field(branch.id()+"/sub",fx,fy-.18,fw,.12,1));
      }
    }
    return new Layout(origin,mount.right(),mount.front(),boards,List.copyOf(fields),List.copyOf(faces));
  }
  /** A terminal's painted exclusion wedge is not part of its usable carriageway. */
  public static double carriagewayCenter(Mesh mesh,Sample at,int flow,double fallback){
    var profile=RoadProfile.layout(mesh,at);
    if(!profile.catalog().twoWay())return fallback;
    int side=flow*profile.outside();
    double inner=profile.medianEdge(side),outer=profile.outer(side);
    double closure=RoadTransitions.dropBoundary(mesh,at);
    if(Double.isFinite(closure))outer=side*Math.min(Math.abs(outer),closure);
    return (inner+outer)*.5*flow;
  }
  public static final int DIAGRAM_GREEN=GREEN;
  private static String diagramHeaderField(State state,String field){
    return state.branches().stream().filter(b->b.id().equals("through")).findFirst().orElse(state.branches().get(0)).id()+"/"+field;
  }
  /** One-time migration of old per-branch headers. Original words remain saved. */
  public static State unifiedHeader(State state){
    if(state.mode()!=Mode.IC_JCT||state.branches().isEmpty())return state;
    var words=new HashMap<>(state.text());
    for(String suffix:List.of("exit","distance")){String primary=diagramHeaderField(state,suffix);
      if(!words.getOrDefault(primary,Text.EMPTY).value().isEmpty())continue;
      for(var branch:state.branches()){var old=words.get(branch.id()+"/"+suffix);if(old!=null&&!old.value().isEmpty()){words.put(primary,old);break;}}
    }
    return words.equals(state.text())?state:new State(state.key(),state.mode(),state.target(),state.diagnostic(),state.revision()+1,state.branches(),words,state.drafts(),state.entry(),state.support(),state.hiddenHeaders(),state.hiddenAccessories(),state.closedLanes());
  }
  private static Layout diagramLayout(State state,RoadGantry.Station st,V origin,V right,V front,double center,double frameFront,double mount){
    int columns=Math.max(1,(int)Math.max(state.branches().stream().filter(b->b.side()<0).count(),state.branches().stream().filter(b->b.side()>0).count()));
    double width=4+Math.max(0,columns-1)*3.9,scale=width/4;
    var boards=new ArrayList<Board>();boards.add(new Board("diagram",center,width,4));
    String primary=diagramHeaderField(state,"exit");primary=primary.substring(0,primary.lastIndexOf('/'));
    boolean exit=state.accessoryVisible(primary,"exit"),distance=state.accessoryVisible(primary,"distance");
    if(exit||distance)boards.add(new Board("diagram/header",center,exit&&distance?3.4:exit?2.35:1.10,.62,4.13));
    var faces=new ArrayList<RoadSurface.Face>();var fields=new ArrayList<Field>();var t=new Layout(origin,right,front,boards,fields,faces);
    for(var b:boards){shell(faces,t,b);rounded(faces,t,b.x()-b.width()/2,b.bottom(),b.x()+b.width()/2,b.top(),.10,FACE,WHITE);rounded(faces,t,b.x()-b.width()/2+.065,b.bottom()+.065,b.x()+b.width()/2-.065,b.top()-.065,.065,FACE+.006,DIAGRAM_GREEN);}
    mounting(faces,t,boards.get(0),st,frameFront,mount);
    if(exit||distance){double offset=exit&&distance?.90:boards.get(1).width()*.29;
      for(double x:new double[]{center-offset,center+offset})box(faces,t,x-.045,3.8,x+.045,4.65,.025,BACK,0xA6B4B2);
    }
    if(exit)fields.add(new Field(diagramHeaderField(state,"exit"),center-(distance?.46:0),4.44,2.1,.40,2));
    if(distance)fields.add(new Field(diagramHeaderField(state,"distance"),center+(exit?1.12:0),4.44,.86,.32,1));
    var counts=new HashMap<Integer,Integer>();
    for(var branch:state.branches()){
      boolean through=branch.id().equals("through")||branch.side()==0;
      if(through){
        fields.add(new Field(branch.id()+"/main",center-.94,3.56,1.65,.38,1));
        fields.add(new Field(branch.id()+"/sub",center-.94,3.22,1.65,.24,1));
        if(state.headerVisible(branch.id())){double x=center+.94,w=1.65,y=3.52,h=.58,badge=.62,left=x-w/2;
        rounded(faces,t,left,y-h/2,left+badge,y+h/2,.035,INK,WHITE);
        rounded(faces,t,left+.035,y-h/2+.035,left+badge-.035,y+h/2-.035,.025,INK+.004,DIAGRAM_GREEN);
        fields.add(new Field(branch.id()+"/route",left+badge/2,y,badge-.10,h-.12,1));
        fields.add(new Field(branch.id()+"/road",x+badge/2,y+.12,w-badge-.12,.28,1));
        fields.add(new Field(branch.id()+"/roadSub",x+badge/2,y-.18,w-badge-.12,.18,1));}
      }else{
        int count=(int)state.branches().stream().filter(b->b.side()==branch.side()).count(),column=counts.merge(branch.side(),1,Integer::sum)-1;
        double x=center+branch.side()*(1.06+column*1.95),w=count==1?1.58:1.75;
        if(state.headerVisible(branch.id()))header(faces,fields,t,branch.id(),x,2.63,w,.52,DIAGRAM_GREEN,DIAGRAM_GREEN);
        fields.add(new Field(branch.id()+"/main",x,2.17,w,.34,1));
        fields.add(new Field(branch.id()+"/sub",x,1.85,w,.22,1));
      }
      var points=branch.path().stream().map(p->new V(center+p.x()*1.74*scale,.22+p.z()*2.65,0)).toList();
      continuousArrow(faces,t,points,.095,INK+.020);
    }
    return new Layout(origin,right,front,boards,List.copyOf(fields),List.copyOf(faces));
  }
  /** Shared vertices form a continuous ribbon. The stem stops inside the head. */
  private static void continuousArrow(List<RoadSurface.Face> out,Layout t,List<V> path,double width,double depth){
    var points=new ArrayList<V>();for(var p:path)if(points.isEmpty()||p.sub(points.get(points.size()-1)).distance(new V(0,0,0))>1e-7)points.add(p);
    if(points.size()<2)return;V tip=points.get(points.size()-1),direction=tip.sub(points.get(points.size()-2));double n=direction.distance(new V(0,0,0));direction=direction.mul(1/n);
    double trim=.22;while(points.size()>1){int last=points.size()-1;V end=points.get(last),before=points.get(last-1);double length=end.sub(before).distance(new V(0,0,0));if(length<=trim){trim-=length;points.remove(last);}else{points.set(last,end.add(before.sub(end).mul(trim/length)));break;}}
    if(points.size()>=2){var offsets=new ArrayList<V>();
      for(int i=0;i<points.size();i++){V before=points.get(Math.max(0,i-1)),at=points.get(i),after=points.get(Math.min(points.size()-1,i+1));V a=at.sub(before),b=after.sub(at);if(a.distance(new V(0,0,0))<1e-7)a=b;if(b.distance(new V(0,0,0))<1e-7)b=a;a=a.mul(1/a.distance(new V(0,0,0)));b=b.mul(1/b.distance(new V(0,0,0)));
        V na=new V(-a.y(),a.x(),0),nb=new V(-b.y(),b.x(),0),sum=na.add(nb);double length=sum.distance(new V(0,0,0));V normal=length<1e-7?nb:sum.mul(1/length);double divisor=normal.dot(nb);offsets.add(normal.mul(Math.min(width*1.25,width/2/Math.max(.4,divisor))));}
      for(int i=1;i<points.size();i++){V a=points.get(i-1),b=points.get(i),oa=offsets.get(i-1),ob=offsets.get(i);out.add(new RoadSurface.Face(List.of(t.at(a.x()-oa.x(),a.y()-oa.y(),depth),t.at(b.x()-ob.x(),b.y()-ob.y(),depth),t.at(b.x()+ob.x(),b.y()+ob.y(),depth),t.at(a.x()+oa.x(),a.y()+oa.y(),depth)),WHITE));}
    }
    arrowTip(out,t,tip.x(),tip.y(),direction.x(),direction.y(),.29,.17,depth+.001);
  }
  /** The 0.42.4 disjoint painted surface writes ordinary text-material depth. No extra cap. */
  public static List<RoadSurface.Face> solidFaces(Layout layout){return SmartSignFaces.compose(layout).stream().filter(f->!SmartSignFaces.front(f,layout)).toList();}
  public static List<RoadSurface.Face> artworkFaces(Layout layout){return SmartSignFaces.compose(layout).stream().filter(f->SmartSignFaces.front(f,layout)).toList();}
  public static V anchor(Mesh mesh,State s){return s.support()!=null?s.support().foot():RoadGantry.station(mesh,s.key().slot()).sample().center();}
  public static boolean valid(Mesh mesh,State s){return freestanding(s.mode())?s.support()!=null:RoadGantry.exists(mesh,s.key().slot());}
  private static Layout speedLayout(Mesh mesh,State state,RoadGantry.Station st,V origin,V right,V front,double frameFront,double mount){
    var boards=new ArrayList<Board>();var fields=new ArrayList<Field>();var faces=new ArrayList<RoadSurface.Face>();
    var live=LaneSections.live(mesh,st.distance()).lanes().stream().filter(l->l.sign()==state.key().flow()).toList();
    for(var lane:live)boards.add(new Board("lane:"+lane.index(),lane.position().sub(st.sample().center()).dot(right),Math.min(3.4,Math.max(1.2,lane.width()-.22)),2.65));
    var profile=RoadProfile.layout(mesh,st.sample());
    if(profile.shoulderWidth()>.25&&!live.isEmpty()){
      int side=profile.catalog().twoWay()?(mesh.settings().options().leftTraffic()?-state.key().flow():state.key().flow()):profile.outside();
      double x=(profile.outer(side)+side*profile.shoulderWidth()/2)*state.key().flow();
      boards.add(new Board("emergency",x,Math.max(1.2,profile.shoulderWidth()-.16),2.65));
    }
    var t=new Layout(origin,right,front,boards,fields,faces);
    for(var b:boards){shell(faces,t,b);rounded(faces,t,b.x()-b.width()/2,0,b.x()+b.width()/2,b.height(),.10,FACE,WHITE);rounded(faces,t,b.x()-b.width()/2+.06,.06,b.x()+b.width()/2-.06,b.height()-.06,.06,FACE+.006,GREEN);mounting(faces,t,b,st,frameFront,mount);
      double x=b.x(),w=b.width();
      if(b.branch().equals("emergency")){
        fields.add(new Field("fixed/emergency",x,2.12,w-.24,.38,1,WHITE,"应急车道"));fields.add(new Field("fixed/emergencySub",x,1.68,w-.24,.24,1,WHITE,"Emergency Lane"));
        double r=Math.min(.44,w*.22);disc(faces,t,x,.84,r,INK,RED);disc(faces,t,x,.84,r*.77,INK+.002,BLUE);
        double a=r*.57;stroke(faces,t,x-a,.84-a,x+a,.84+a,.09,INK+.006,RED);stroke(faces,t,x-a,.84+a,x+a,.84-a,.09,INK+.006,RED);
      }else{
        fields.add(new Field(b.branch()+"/vehicle",x,2.12,w-.24,.40,1));fields.add(new Field(b.branch()+"/vehicleSub",x,1.66,w-.24,.28,1));
        double r=Math.min(.47,w*.18),dx=w*.29;
        disc(faces,t,x-dx,.77,r,INK,RED);disc(faces,t,x-dx,.77,r*.79,INK+.002,WHITE);disc(faces,t,x+dx,.77,r,INK,BLUE);
        discCap(faces,t,x+dx,.77,r,-.62,INK+.004,WHITE);
        fields.add(new Field(b.branch()+"/maxSpeed",x-dx,.77,r*1.35,r*.9,1,0x101818));fields.add(new Field(b.branch()+"/minSpeed",x+dx,.77,r*1.35,r*.9,1));
        boldArrow(faces,t,x,1.04,x,.37,Math.min(.15,w*.04),INK);
      }
    }return new Layout(origin,right,front,List.copyOf(boards),List.copyOf(fields),List.copyOf(faces));
  }
  /** One black panel per live lane, physically suspended below the lower truss. */
  private static Layout indicatorLayout(Mesh mesh,State state,RoadGantry.Station st,V right,V front,double mount){
    double height=1.2,drop=.2,frameFront=st.kind()==RoadInfrastructure.Gantry.FRAME?.5:.225;
    V origin=st.sample().center().add(new V(0,RoadGantry.lowerBeamY(st)-st.sample().center().y()-drop-height,0)).add(front.mul(mount));
    var boards=new ArrayList<Board>();var faces=new ArrayList<RoadSurface.Face>();
    var live=LaneSections.live(mesh,st.distance()).lanes().stream().filter(l->l.sign()==state.key().flow()).toList();
    for(var lane:live)boards.add(new Board("lane:"+lane.index(),lane.position().sub(st.sample().center()).dot(right),Math.min(1.3,lane.width()-.2),height));
    var profile=RoadProfile.layout(mesh,st.sample());
    if(profile.shoulderWidth()>.25&&!live.isEmpty()){
      int side=profile.catalog().twoWay()?(mesh.settings().options().leftTraffic()?-state.key().flow():state.key().flow()):profile.outside();
      boards.add(new Board("emergency",(profile.outer(side)+side*profile.shoulderWidth()/2)*state.key().flow(),1.2,height));
    }
    var layout=new Layout(origin,right,front,boards,List.of(),faces);
    for(var b:boards){
      shell(faces,layout,b);rounded(faces,layout,b.x()-b.width()/2,0,b.x()+b.width()/2,height,.055,FACE,0x121A19);
      for(double x:new double[]{b.x()-.30,b.x()+.30})box(faces,layout,x-.045,height-.10,x+.045,height+drop+.10,frameFront-mount-.06,BACK,0xA6B4B2);
      if(b.branch().equals("emergency")){
        disc(faces,layout,b.x(),height/2,.43,INK,RED);disc(faces,layout,b.x(),height/2,.32,INK+.002,0x121A19);
        stroke(faces,layout,b.x()-.29,height/2,b.x()+.29,height/2,.105,INK+.004,RED);
      }else if(state.closedLanes().contains(b.branch())){
        stroke(faces,layout,b.x()-.32,.28,b.x()+.32,.92,.15,INK,RED);
        stroke(faces,layout,b.x()-.32,.92,b.x()+.32,.28,.15,INK,RED);
      }else boldArrowColor(faces,layout,b.x(),.99,b.x(),.22,.16,INK,0x35FF66);
    }
    return new Layout(origin,right,front,List.copyOf(boards),List.of(),List.copyOf(faces));
  }
  private static void boldArrowColor(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double width,double z,int color){
    var temporary=new ArrayList<RoadSurface.Face>();boldArrow(temporary,t,x0,y0,x1,y1,width,z);
    for(var face:temporary)out.add(new RoadSurface.Face(face.points(),color));
  }
  /** Circular segment, including its straight chord, rather than a short underline. */
  private static void discCap(List<RoadSurface.Face> out,Layout t,double x,double y,double r,double fraction,double z,int color){
    double start=Math.PI-Math.asin(fraction),end=2*Math.PI+Math.asin(fraction);var points=new ArrayList<V>();
    for(int i=0;i<=24;i++){double a=start+(end-start)*i/24;points.add(t.at(x+r*Math.cos(a),y+r*Math.sin(a),z));}
    out.add(new RoadSurface.Face(points,color));
  }
  private static void disc(List<RoadSurface.Face> out,Layout t,double x,double y,double r,double z,int color){var points=new ArrayList<V>();for(int i=0;i<48;i++){double a=2*Math.PI*i/48;points.add(t.at(x+r*Math.cos(a),y+r*Math.sin(a),z));}out.add(new RoadSurface.Face(points,color));}
  private static void sideMount(List<RoadSurface.Face> out,Layout t,Board b){
    Board top=accessoryBoard(t,b);boolean full=top!=null&&top.width()>=b.width()*.90;
    for(double x:new double[]{b.x()-b.width()*.29,b.x()+b.width()*.29}){
      box(out,t,x-.06,.2,x+.06,full?4.15:b.top()-.18,-.12,BACK,0xB9C8C6);
      for(double y:new double[]{1.1,2.1})box(out,t,x-.12,y-.09,x+.12,y+.09,-.30,BACK,0x80918F);
    }
    if(top!=null&&!full)for(double x:new double[]{top.x()-top.width()*.29,top.x()+top.width()*.29})box(out,t,x-.06,b.top()-.18,x+.06,top.top(),-.12,BACK,0xB9C8C6);
  }
  /** Conservative physical envelopes in the same frame as the rendered mast.
   * Touching a support top is allowed; a wall penetrating the mast is not. */
  public static List<RoadStructures.Part> mountParts(Mesh mesh,State state){
    if(!freestanding(state.mode())||state.support()==null)return List.of();
    var t=layout(mesh,state);if(t.boards().isEmpty())return List.of();
    boolean roadside=state.mode()==Mode.ROADSIDE,advance=state.mode()==Mode.JUNCTION_ADVANCE;
    double depth=roadside?-.08:advance?-.03:(BACK+FACE)/2;
    double height=roadside?7.87:advance?6.85:2.145;
    var material=RoadStructures.Material.STEEL;var out=new ArrayList<RoadStructures.Part>();
    V foot=state.support().foot().add(t.front().mul(depth));
    boolean gantry=roadside&&state.support().gantrySpan()!=0;
    if(gantry)out.addAll(halfGantry(t,state.support().gantrySpan()));
    else out.add(new RoadStructures.Part(foot,foot,.30,height,true,material));
    for(var b:t.boards())out.add(new RoadStructures.Part(t.at(b.x()-b.width()/2,b.bottom(),(BACK+FACE)/2),t.at(b.x()+b.width()/2,b.bottom(),(BACK+FACE)/2),FACE-BACK,b.height(),false,material));
    if((roadside&&!gantry)||advance){double lo=Math.min(0,t.boards().stream().mapToDouble(b->b.x()-b.width()/2+.14).min().orElse(0)),hi=Math.max(0,t.boards().stream().mapToDouble(b->b.x()+b.width()/2-.14).max().orElse(0));
      for(double y:roadside?new double[]{1.1,2.1}:new double[]{.55,1.45})out.add(new RoadStructures.Part(t.at(lo,y-.085,roadside?-.22:-.03),t.at(hi,y-.085,roadside?-.22:-.03),.17,.17,false,material));}
    return List.copyOf(out);
  }
  /** Render and clearance checks use the same two posts, chords and diagonal braces. */
  private static List<RoadStructures.Part> halfGantry(Layout t,double span){
    var out=new ArrayList<RoadStructures.Part>();var steel=RoadStructures.Material.STEEL;
    for(double x:new double[]{0,span}){
      var foot=t.at(x,-5.2,-.22);
      out.add(new RoadStructures.Part(foot,foot,.34,7.47,true,steel));
    }
    for(double y:new double[]{1.0,2.10})
      out.add(new RoadStructures.Part(t.at(0,y,-.22),t.at(span,y,-.22),.26,.17,false,steel));
    int bays=(int)Math.ceil(Math.abs(span)/2);
    for(int i=0;i<bays;i++){
      var a=t.at(span*i/bays,i%2==0?1.16:2.10,-.22);
      var b=t.at(span*(i+1)/bays,i%2==0?2.10:1.16,-.22);
      out.add(new RoadStructures.Part(a,b,.12,.12,false,steel));
    }
    return out;
  }
  private static void pole(List<RoadSurface.Face> out,Layout t,List<Board> boards){
    // Two round horizontal arms and a flanged tubular post, entirely in the
    // same transform as the boards; the base is on the separator's paved curb.
    tube(out,t,0,-5.2,0,2.55,.12,-.08,0xB8C7C5);
    box(out,t,-.15,-5.2,.15,-5.03,-.23,.07,0x737F80);
    for(double x:new double[]{-.11,.11})for(double z:new double[]{-.19,.03})box(out,t,x-.022,-5.03,x+.022,-4.97,z-.022,z+.022,0xD4DFDA);
    double lo=boards.stream().mapToDouble(b->b.x()-b.width()/2+.25).min().orElse(0),hi=boards.stream().mapToDouble(b->b.x()+b.width()/2-.25).max().orElse(0);
    for(double y:new double[]{1.1,2.1})tube(out,t,Math.min(0,lo),y,Math.max(0,hi),y,.085,-.22,0xBDCBC9);
  }
  private static void tube(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double r,double z,int color){
    double n=Math.hypot(x1-x0,y1-y0),nx=-(y1-y0)/n,ny=(x1-x0)/n;var a=new ArrayList<V>();var b=new ArrayList<V>();
    for(int i=0;i<12;i++){double v=2*Math.PI*i/12;a.add(t.at(x0+nx*r*Math.cos(v),y0+ny*r*Math.cos(v),z+r*Math.sin(v)));b.add(t.at(x1+nx*r*Math.cos(v),y1+ny*r*Math.cos(v),z+r*Math.sin(v)));}
    for(int i=0;i<12;i++){int j=(i+1)%12;out.add(new RoadSurface.Face(List.of(a.get(i),a.get(j),b.get(j),b.get(i)),color));}out.add(new RoadSurface.Face(a,color));Collections.reverse(b);out.add(new RoadSurface.Face(b,color));
  }
  private static void header(List<RoadSurface.Face> out,List<Field> fields,Layout t,String id,double x,double y,double width,double height){
    header(out,fields,t,id,x,y,width,height,GREEN,DARK_GREEN);
  }
  private static void header(List<RoadSurface.Face> out,List<Field> fields,Layout t,String id,double x,double y,double width,double height,int badgeColor,int roadColor){
    double badge=Math.min(.97,width*.28),left=x-width/2;
    rounded(out,t,left,y-height/2,x+width/2,y+height/2,.035,INK,WHITE);
    rounded(out,t,left+.045,y-height/2+.045,left+badge,y+height/2-.045,.025,INK+.004,badgeColor);
    fields.add(new Field(id+"/route",left+(badge+.045)/2,y,badge-.10,height-.15,1));
    double tx=(left+badge+x+width/2)/2,tw=width-badge-.10;
    fields.add(new Field(id+"/road",tx,y+height*.14,tw,height*.45,1,roadColor));
    fields.add(new Field(id+"/roadSub",tx,y-height*.29,tw,height*.25,1,roadColor));
  }
  private static void mounting(List<RoadSurface.Face> out,Layout t,Board b,RoadGantry.Station st,double frameFront,double mount){
    double beam=RoadGantry.lowerBeamY(st)-t.origin().y();
    for(double x:new double[]{b.x()-b.width()*.29,b.x()+b.width()*.29}){
      box(out,t,x-.055,.20,x+.055,b.top()-.18,.015,BACK,0xA6B4B2);
      // A steel saddle contacts the actual beam face at both horizontal rails.
      for(double y:new double[]{beam,beam+(st.kind()==RoadInfrastructure.Gantry.FRAME?.82:.7)})
        if(y<b.top()-.15)box(out,t,x-.12,y-.03,x+.12,y+.20,frameFront-mount-.035,BACK,0x7D8D8B);
      if(b.branch().equals("diagram")||b.branch().startsWith("lane:")||b.branch().equals("emergency"))continue;
      Board top=accessoryBoard(t,b);if(top!=null){double sx=Math.abs(top.width()-Math.min(b.width()-.2,5.4))<1e-7?x:Math.max(top.x()-top.width()*.40,Math.min(top.x()+top.width()*.40,x));
        box(out,t,sx-.045,b.top()-.18,sx+.045,b.top()+.70,.025,BACK,0xA6B4B2);
      }
    }
  }
  private static List<double[]> outline(double x0,double y0,double x1,double y1,double radius){
    double r=Math.min(radius,Math.min(x1-x0,y1-y0)/2);var points=new ArrayList<double[]>();
    for(int corner=0;corner<4;corner++){double cx=corner==0||corner==3?x1-r:x0+r,cy=corner<2?y1-r:y0+r;
      for(int step=0;step<=3;step++){double a=Math.toRadians(corner*90+step*30);points.add(new double[]{cx+Math.cos(a)*r,cy+Math.sin(a)*r});}}
    return points;
  }
  private static void rounded(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double radius,double z,int color){
    out.add(new RoadSurface.Face(outline(x0,y0,x1,y1,radius).stream().map(p->t.at(p[0],p[1],z)).toList(),color));
  }
  private static void shell(List<RoadSurface.Face> out,Layout t,Board b){
    var rim=outline(b.x()-b.width()/2,b.bottom(),b.x()+b.width()/2,b.top(),.10);
    var back=new ArrayList<V>();for(var p:rim)back.add(t.at(p[0],p[1],BACK));Collections.reverse(back);out.add(new RoadSurface.Face(back,0x879793));
    for(int i=0;i<rim.size();i++){var a=rim.get(i);var z=rim.get((i+1)%rim.size());out.add(new RoadSurface.Face(List.of(t.at(a[0],a[1],BACK),t.at(z[0],z[1],BACK),t.at(z[0],z[1],FACE),t.at(a[0],a[1],FACE)),0xB2C0BA));}
  }
  private static void box(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double z0,double z1,int color){
    quad(out,t,x0,y0,x1,y1,z1,color);out.add(new RoadSurface.Face(List.of(t.at(x1,y0,z0),t.at(x0,y0,z0),t.at(x0,y1,z0),t.at(x1,y1,z0)),color));
    for(double x:new double[]{x0,x1}){var p=new ArrayList<>(List.of(t.at(x,y0,z0),t.at(x,y0,z1),t.at(x,y1,z1),t.at(x,y1,z0)));if(x==x1)Collections.reverse(p);out.add(new RoadSurface.Face(p,color));}
    for(double y:new double[]{y0,y1}){var p=new ArrayList<>(List.of(t.at(x0,y,z0),t.at(x1,y,z0),t.at(x1,y,z1),t.at(x0,y,z1)));if(y==y1)Collections.reverse(p);out.add(new RoadSurface.Face(p,color));}
  }
  private static void arrowTip(List<RoadSurface.Face> out,Layout t,double x,double y,double dx,double dy,double length,double width,double z){
    double n=Math.hypot(dx,dy);if(n<1e-7)return;dx/=n;dy/=n;
    out.add(new RoadSurface.Face(List.of(t.at(x,y,z),t.at(x-dx*length-dy*width,y-dy*length+dx*width,z),t.at(x-dx*length+dy*width,y-dy*length-dx*width,z)),WHITE));
  }
  private static void boldArrow(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double width,double depth){
    double n=Math.hypot(x1-x0,y1-y0),dx=(x1-x0)/n,dy=(y1-y0)/n;
    stroke(out,t,x0,y0,x1-dx*.30,y1-dy*.30,width,depth,WHITE);arrowTip(out,t,x1,y1,dx,dy,.40,width*1.65,depth);
  }
  private static void quad(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double z,int color){out.add(new RoadSurface.Face(List.of(t.at(x0,y0,z),t.at(x1,y0,z),t.at(x1,y1,z),t.at(x0,y1,z)),color));}
  private static void stroke(List<RoadSurface.Face> out,Layout t,double x0,double y0,double x1,double y1,double width,double z,int color){double n=Math.hypot(x1-x0,y1-y0);if(n<1e-6)return;double dx=-(y1-y0)/n*width/2,dy=(x1-x0)/n*width/2;out.add(new RoadSurface.Face(List.of(t.at(x0-dx,y0-dy,z),t.at(x1-dx,y1-dy,z),t.at(x1+dx,y1+dy,z),t.at(x0+dx,y0+dy,z)),color));}
  private SmartSigns(){}
}
