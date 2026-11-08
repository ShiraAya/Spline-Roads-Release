package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.*;

/** Uses the shared world preview. Hiding a screen does not discard its checked draft. */
public final class LaneRampScreen extends Screen {
  private static LaneRampScreen active;
  private static long sequence;
  private final CompoundTag payload;private final int rampLanes;
  private LanePoints.Path path;private boolean pending,gradeOverride,allowTunnel;
  private LanePoints.Departure departure;private LanePoints.Arrival arrival;
  private LanePoints.Elevation elevation;private LanePoints.Landing landing;
  private List<RoadGeometry.Mesh> checkedRoads=List.of(),conflictRoads=List.of();
  private String radiusText,transitionText;
  private EditBox radius,transition;private Button build,previewButton,details,back,worldButton,deleteButton;private boolean diagnostic;private UUID token;private RoadGeometry.Mesh checkedMesh;
  private final List<RoadGeometry.Mesh> contextRoads=new ArrayList<>();
  private long request=++sequence,pendingSince;private int x,y,control,panelWidth,panelHeight,widgetHeight=20;private double uiScale=1;
  private String status="设置完成后点击计算预览；实景预览可切换到世界。Shift＋右键清除选点。";
  public LaneRampScreen(CompoundTag t){
    super(Component.literal(t.hasUUID("Id")?"编辑匝道":"匝道连接器"));payload=t.copy();
    var o=LanePointCodec.options(t.getCompound("Options"));rampLanes=o.lanes();path=o.path();departure=o.departure();arrival=o.arrival();elevation=o.elevation();landing=o.landing();gradeOverride=o.gradeOverride();allowTunnel=o.allowTunnel();radiusText=number(o.radius());transitionText=number(o.transition());
    if(t.getBoolean("SourceRamp"))departure=LanePoints.Departure.BRANCH;
    else if(t.getBoolean("SourceTunnel")||t.getBoolean("SourceHighway"))departure=LanePoints.Departure.EXTRA;
    if(t.getBoolean("TargetRamp"))arrival=LanePoints.Arrival.FLOW;
    else if(t.getBoolean("TargetTunnel")||t.getBoolean("TargetHighway"))arrival=LanePoints.Arrival.EXTRA;
    for(Tag value:t.getList("ContextRoads",Tag.TAG_COMPOUND))contextRoads.add(RoadRecord.load((CompoundTag)value).mesh());
    if(t.contains("Road"))checkedMesh=RoadRecord.load(t.getCompound("Road")).mesh();
  }
  private static LanePoints.Arrival nextArrival(LanePoints.Arrival current){
    var choices=List.of(LanePoints.Arrival.MERGE,LanePoints.Arrival.ADD,LanePoints.Arrival.EXTRA,LanePoints.Arrival.FLOW);
    return choices.get((choices.indexOf(current)+1)%choices.size());
  }
  private static String number(double value){return String.format(Locale.ROOT,"%s",value==(int)value?Integer.toString((int)value):Double.toString(value));}
  public static void open(CompoundTag t){
    if(active==null||!t.hasUUID("Id")||!active.payload.hasUUID("Id")||!t.getUUID("Id").equals(active.payload.getUUID("Id"))||t.getInt("Signature")!=active.payload.getInt("Signature")){
      clear();active=new LaneRampScreen(t);
    }
    resume();
  }
  public static void resume(){if(active!=null){ClientRoads.beginEditor(active);active.showWorld();Minecraft.getInstance().setScreen(active);}}
  private void showWorld(){
    // Request ids reject older drafts in this screen. The shared editor owner
    // also prevents a valid hidden result from replacing a different tool's preview.
    if(!ClientRoads.ownsPreview(this))return;
    ClientRoads.conflictPreview=!conflictRoads.isEmpty();ClientRoads.preview=conflictRoads.isEmpty()?checkedMesh:conflictRoads.get(0);ClientRoads.nodePreviews=conflictRoads.isEmpty()?checkedRoads:conflictRoads;
  }
  public static void resume(CompoundTag t){if(active==null&&t.contains("Fallback"))open(t.getCompound("Fallback"));else resume();}
  public static boolean clear(){
    boolean owned=ClientRoads.endEditor(active);
    if(owned){ClientRoads.conflictPreview=false;ClientRoads.preview=null;ClientRoads.nodePreviews=List.of();}
    active=null;++sequence;return owned;
  }
  public static void checkedReply(CompoundTag t){if(active!=null)active.checked(t);}
  public static void checkedReply(CompoundTag t,RoadPreviewData prepared){if(active!=null)active.checked(t,prepared);}
  static void deliveryRecovery(){if(active!=null&&active.pending&&active.token==null){active.cancelPreview();active.request=++sequence;active.failed("预览传输繁忙，请重新预览");}}
  public static void failedPending(String message,CompoundTag reply){if(active!=null&&active.pending&&active.token!=null)active.failed(message,reply);}
  private int row(int offset){return y+(int)Math.round(offset*uiScale);}
  @Override protected void init(){
    x=12;y=Math.max(6,(height-360)/2);panelWidth=width-24;panelHeight=height-2*y;control=Math.min(330,(panelWidth-36)/2);uiScale=Math.min(1,(height-24)/340.0);widgetHeight=Math.max(14,(int)Math.round(20*uiScale));
    for(var choice:LanePoints.Path.values())addRenderableWidget(Button.builder(Component.literal((choice==path?"● ":"")+choice.label),b->{path=choice;invalidate();rebuildWidgets();}).bounds(x+12+choice.ordinal()*(control/5),row(51),control/5-2,widgetHeight).build());
    var sourceButton=addRenderableWidget(Button.builder(Component.literal("汇出："+departure.label+(payload.getBoolean("SourceRamp")?"（匝道）":payload.getBoolean("SourceHighway")?"（高速）":payload.getBoolean("SourceTunnel")?"（隧道）":"")),b->{departure=LanePoints.Departure.values()[(departure.ordinal()+1)%LanePoints.Departure.values().length];invalidate();rebuildWidgets();}).bounds(x+12,row(81),control/2-5,widgetHeight).build());
    var targetButton=addRenderableWidget(Button.builder(Component.literal("汇入："+arrival.label+(payload.getBoolean("TargetRamp")?"（匝道）":payload.getBoolean("TargetHighway")?"（高速）":payload.getBoolean("TargetTunnel")?"（隧道）":"")),b->{arrival=nextArrival(arrival);invalidate();rebuildWidgets();}).bounds(x+17+control/2,row(81),control/2-5,widgetHeight).build());
    sourceButton.active=!payload.getBoolean("SourceHighway")&&!payload.getBoolean("SourceTunnel")&&!payload.getBoolean("SourceRamp");targetButton.active=!payload.getBoolean("TargetHighway")&&!payload.getBoolean("TargetTunnel")&&!payload.getBoolean("TargetRamp");
    addRenderableWidget(Button.builder(Component.literal(elevation.label),b->{elevation=LanePoints.Elevation.values()[(elevation.ordinal()+1)%LanePoints.Elevation.values().length];invalidate();rebuildWidgets();}).bounds(x+12,row(108),control/2-5,widgetHeight).build());
    addRenderableWidget(Button.builder(Component.literal(landing.label),b->{landing=LanePoints.Landing.values()[(landing.ordinal()+1)%LanePoints.Landing.values().length];invalidate();rebuildWidgets();}).bounds(x+17+control/2,row(108),control/2-5,widgetHeight).build());
    radius=new EditBox(font,x+12,row(146),control/2-5,widgetHeight,Component.literal("参考半径"));radius.setMaxLength(32);radius.setValue(radiusText);radius.setResponder(s->{radiusText=s;invalidate();});radius.setTooltip(Tooltip.create(Component.literal("规划参考半径：圆弧按此半径布线；直接连接及端口拟合的实际最小半径以已检查预览为准。")));addRenderableWidget(radius);
    transition=new EditBox(font,x+17+control/2,row(146),control/2-5,widgetHeight,Component.literal("过渡长度"));transition.setMaxLength(32);transition.setValue(transitionText);transition.setResponder(s->{transitionText=s;invalidate();});addRenderableWidget(transition);
    addRenderableWidget(Button.builder(Component.literal(gradeOverride?"坡比超限：开（普通25% / 涉高速20%）":"坡比超限：关（普通20% / 涉高速15%）"),b->{gradeOverride=!gradeOverride;invalidate();rebuildWidgets();}).bounds(x+12,row(173),control,widgetHeight).build());
    addRenderableWidget(Button.builder(Component.literal("允许隧道："+(allowTunnel?"开":"关")+"（地下接头始终允许）"),b->{allowTunnel=!allowTunnel;invalidate();rebuildWidgets();}).bounds(x+12,row(197),control,widgetHeight).build());
    int third=(control-8)/3;
    previewButton=addRenderableWidget(Button.builder(Component.literal("计算预览"),b->preview()).bounds(x+12,row(227),third,widgetHeight).build());previewButton.active=!pending;
    build=addRenderableWidget(Button.builder(Component.literal(payload.hasUUID("Id")?"保存匝道":"建造 A → B"),b->submit()).bounds(x+20+third*2,row(227),third,widgetHeight).build());build.active=token!=null&&!pending;
    back=addRenderableWidget(Button.builder(Component.literal(pending&&token==null?"取消计算并返回":"返回实景 / 预览"),b->{if(pending&&token==null){cancelPreview();invalidate();}showWorld();ManualPlannerClient.worldPreview();onClose();}).bounds(x+16+third,row(227),third,widgetHeight).build());
    worldButton=back;
    if(ManualPlannerClient.hasMap())addRenderableWidget(Button.builder(Component.literal("规划地图"),b->{showWorld();ManualPlannerClient.mapPreview(this);}).bounds(x+12,row(251),third,widgetHeight).build());
    details=addRenderableWidget(Button.builder(Component.literal("失败详情"),b->Minecraft.getInstance().setScreen(new FailureDetails(this,status))).bounds(x+16+third,row(251),third,widgetHeight).build());details.visible=diagnostic;details.active=diagnostic;
    if(payload.hasUUID("Id")){
      deleteButton=addRenderableWidget(Button.builder(Component.literal("删除匝道"),b->{
        if(pending)cancelPreview();invalidate();
        var t=new CompoundTag();t.putString("Action","laneRampDeletePreview");t.putUUID("Id",payload.getUUID("Id"));t.putInt("Signature",payload.getInt("Signature"));
        t.put("From",payload.getCompound("From").copy());t.put("To",payload.getCompound("To").copy());ClientRoads.sendAction(t);
      }).bounds(x+20+third*2,row(251),third,widgetHeight).build());deleteButton.active=!pending;
    }

  }
  private void cancelPreview(){var t=new CompoundTag();t.putString("Action","laneRampCancel");t.putLong("Request",request);ClientRoads.sendAction(t);}
  private void invalidate(){ClientRoads.editorChanged(this);conflictRoads=List.of();ClientRoads.conflictPreview=false;if(pending)cancelPreview();diagnostic=false;if(details!=null){details.visible=false;details.active=false;}token=null;checkedMesh=null;checkedRoads=List.of();pending=false;if(build!=null)build.active=false;if(previewButton!=null)previewButton.active=true;ClientRoads.preview=null;ClientRoads.nodePreviews=List.of();request=++sequence;status=switch(departure){
      case TEMPORARY -> "保留车道分离：直连匝道，主路暂时关闭该车道；净空安全后恢复。地面封闭绿化、高架直角孔区。请预览。";
      case DETACH -> "整车道分离：直连匝道，主路下游取消该车道。请预览。";
      case BRANCH -> "普通分流：原车道继续直行；不是先关闭再恢复。请预览。";
      case EXTRA -> "额外扩出：保持既有车道，另拓出匝道。请预览。";
    };}
  private CompoundTag command(String action){var t=new CompoundTag();t.putString("Action",action);t.put("From",payload.getCompound("From").copy());t.put("To",payload.getCompound("To").copy());if(payload.hasUUID("Id")){t.putUUID("Id",payload.getUUID("Id"));t.putInt("Signature",payload.getInt("Signature"));}t.put("Options",LanePointCodec.options(new LanePoints.Options(path,departure,arrival,Double.parseDouble(radiusText),Double.parseDouble(transitionText),elevation,landing,gradeOverride,allowTunnel,rampLanes)));t.putLong("Request",request);return t;}
  private void preview(){try{invalidate();var t=command("laneRampPreview");pending=true;pendingSince=System.nanoTime();previewButton.active=false;if(deleteButton!=null)deleteButton.active=false;ClientRoads.sendAction(t);status="正在后台检查方向、汇入范围与净空，可取消计算…";}catch(IllegalArgumentException e){failed(e.getMessage());}}
  public void checked(CompoundTag t){checked(t,null);}
  private void checked(CompoundTag t,RoadPreviewData prepared){
    if(t.getLong("Request")!=request)return;pending=false;
    if(t.contains("Error")){if(prepared==null)failed(t.getString("Error"),t);else failed(t.getString("Error"),prepared.conflicts());return;}
    try{var ready=prepared==null?RoadPreviewData.preparePayload(t).lane():prepared.lane();var r=ready.road();checkedMesh=ready.mesh();checkedRoads=ready.roads();showWorld();token=t.getUUID("Token");build.active=true;previewButton.active=true;worldButton.active=true;if(deleteButton!=null)deleteButton.active=true;
      double offset=t.getDouble("TargetOffset");String landing=payload.getCompound("To").hasUUID("Junction")?"":String.format(Locale.ROOT," 汇入口：沿 B 行驶方向 %+.1f 格。",offset);
      String grade=t.contains("GradeLimit")?" 坡比上限 "+LaneRampGrade.label(t.getDouble("GradeLimit"))+"。":"";
      if(t.contains("GradeAvailable"))grade=String.format(Locale.ROOT," 水平 %.1f / 可布坡 %.1f 格，当前变程需≥%.1f 格；实际最大 %.2f%%，上限 %s。",t.getDouble("GradeHorizontal"),t.getDouble("GradeAvailable"),t.getDouble("GradeMinimum"),t.getDouble("ActualGrade")*100,LaneRampGrade.label(t.getDouble("GradeLimit")));
      String closure=t.getBoolean("TemporaryClosure")?(t.getBoolean("RectangularClosure")?String.format(Locale.ROOT," 主路从 A 暂时关闭此车道，下游 %.1f 格处恢复；封闭区不收尖。",t.getDouble("RestoredAfter")):String.format(Locale.ROOT," 主路从A暂时关闭此车道，下游 %.1f 格开始恢复、%.1f 格恢复完整。",t.getDouble("ReopenAfter"),t.getDouble("RestoredAfter"))):"";
      String targetClosure=t.getBoolean("TargetClosure")?String.format(Locale.ROOT," 目标车道在汇入口前 %.1f 格开始封闭，到匝道完整接入后开放；其他车道保持通行。",t.getDouble("TargetClosedBefore")):"";
      double minimumRadius=ready.minimumRadius();
      String radiusLabel=Double.isFinite(minimumRadius)?String.format(Locale.ROOT,"%.2f 格",minimumRadius):"直线";
      status=String.format(Locale.ROOT,"预览有效：%s，长 %.1f 格，实际最小半径 %s。%s%s%s%s 右键空气返回并建造。",t.contains("ResolvedPath")?LanePoints.Path.valueOf(t.getString("ResolvedPath")).label:path.label,r.mesh().length(),radiusLabel,landing,closure,targetClosure,grade);
      
    }catch(IllegalArgumentException e){failed(e.getMessage());}
  }
  private void submit(){try{if(token==null||pending)return;var t=command("laneRamp");t.putUUID("Token",token);pending=true;pendingSince=System.nanoTime();status="正在建造：服务端校验接头、生成设施并写入道路，请勿重复提交。";build.active=false;previewButton.active=false;ClientRoads.sendAction(t);}catch(IllegalArgumentException e){failed(e.getMessage());}}
  public void failed(String s){failed(s,new CompoundTag());}
  void completed(){pending=false;token=null;status="匝道已建成";if(build!=null)build.active=false;if(previewButton!=null)previewButton.active=false;}
  public void failed(String s,CompoundTag reply){
    var roads=new ArrayList<RoadGeometry.Mesh>();
    for(Tag value:reply.getList("ConflictRoads",Tag.TAG_COMPOUND))try{roads.add(RoadRecord.load((CompoundTag)value).mesh());}catch(IllegalArgumentException ignored){}
    failed(s,List.copyOf(roads));}
  void failed(String s,List<RoadGeometry.Mesh> roads){
    if(deleteButton!=null)deleteButton.active=true;
    conflictRoads=roads;status=RoadConflictIds.display(s)+(roads.isEmpty()?"":"；冲突道路已标红，返回实景查看，Shift＋右键清除。");diagnostic=true;if(details!=null){details.visible=true;details.active=true;}pending=false;if(build!=null)build.active=false;if(previewButton!=null)previewButton.active=true;token=null;checkedMesh=null;checkedRoads=List.of();showWorld();}
  void failureGeometry(String message,List<RoadGeometry.Mesh> roads){
    conflictRoads=roads;status=RoadConflictIds.display(message)+(roads.isEmpty()?"":"；冲突道路已标红，返回实景查看，Shift＋右键清除。");showWorld();
  }
  private static String brief(String message){
    int end=message.indexOf('；');if(end<0)end=message.indexOf('。');
    String first=end>0?message.substring(0,end):message;
    return (first.length()>70?first.substring(0,70)+"…":first)+"。点击“失败详情”查看冲突坐标和完整原因。";
  }
  /** Full diagnostic page instead of overflowing the six-line connector footer. */
  private static final class FailureDetails extends Screen {
    private final LaneRampScreen parent;private final String message;
    FailureDetails(LaneRampScreen parent,String message){super(Component.literal("匝道建造失败详情"));this.parent=parent;this.message=message;}
    @Override protected void init(){addRenderableWidget(Button.builder(Component.literal("返回匝道设置"),b->onClose()).bounds((width-160)/2,height-30,160,20).build());}
    @Override public void render(GuiGraphics g,int mx,int my,float dt){
      g.fill(12,12,width-12,height-12,0xf015222e);
      g.drawString(font,"建造未通过：完整诊断",24,24,0xffcf8c,false);
      g.drawWordWrap(font,Component.literal(message),24,44,Math.max(100,width-48),0xffffff);
      super.render(g,mx,my,dt);
    }
    @Override public void onClose(){Minecraft.getInstance().setScreen(parent);}
    @Override public boolean isPauseScreen(){return false;}
  }
  @Override public void onClose(){super.onClose();}
  @Override public boolean isPauseScreen(){return false;}
  // Opening, resizing and editing never dispatch expensive planning requests.
  // Only the explicit preview button may start a new checked draft.
  private void map(GuiGraphics g,int left,int top,int w,int h){
    g.fill(left,top,left+w,top+h,0xff09161f);
    var roads=new ArrayList<>(contextRoads);roads.addAll(checkedRoads);roads.addAll(conflictRoads);if(checkedMesh!=null)roads.add(checkedMesh);
    if(roads.isEmpty()){g.drawString(font,"选择 A、B 后计算俯视预览",left+8,top+30,0xafc8d3,false);return;}
    double minX=roads.stream().mapToDouble(m->m.min().x()).min().orElse(0),maxX=roads.stream().mapToDouble(m->m.max().x()).max().orElse(1);
    double minZ=roads.stream().mapToDouble(m->m.min().z()).min().orElse(0),maxZ=roads.stream().mapToDouble(m->m.max().z()).max().orElse(1);
    double scale=Math.min((w-24)/Math.max(1,maxX-minX),(h-42)/Math.max(1,maxZ-minZ));
    double ox=left+(w-(maxX-minX)*scale)/2,oy=top+22+(h-42-(maxZ-minZ)*scale)/2;
    for(var road:roads){int color=conflictRoads.contains(road)?0xffe06060:road==checkedMesh?0xff61cde3:0xff536b7a;
      for(int i=0;i<road.samples().size();i+=2){var p=road.samples().get(i);int px=(int)(ox+(p.center().x()-minX)*scale),py=(int)(oy+(p.center().z()-minZ)*scale),r=Math.max(1,(int)Math.ceil(p.halfWidth()*scale));
        g.fill(Math.max(left+2,px-r),Math.max(top+20,py-r),Math.min(left+w-2,px+r+1),Math.min(top+h-2,py+r+1),color);
      }
    }
    if(checkedMesh!=null)for(int i=0;i<2;i++){var p=(i==0?checkedMesh.first():checkedMesh.last()).center();g.drawString(font,i==0?"A":"B",(int)(ox+(p.x()-minX)*scale),(int)(oy+(p.z()-minZ)*scale),0xffffff,false);}
    // Minecraft north is negative Z. World X/Z are projected directly, never
    // rotated by the player's yaw or the order in which A and B were selected.
    g.drawString(font,"北 ↑  N",left+w-55,top+6,0xffffff,false);
    g.drawString(font,pending?"计算中…":checkedMesh==null?"道路位置 · 等待预览":"俯视预览",left+8,top+6,0x9ed7e6,false);
  }
  @Override public void render(GuiGraphics g,int mx,int my,float dt){
    if(back!=null)back.setMessage(Component.literal(pending&&token==null?"取消计算并返回":"返回实景 / 预览"));
    g.fill(x,y,x+panelWidth,y+panelHeight,0xea15222e);
    g.drawString(font,payload.hasUUID("Id")?"编辑匝道 · A 汇出 → B 汇入":"匝道连接器 · A 汇出 → B 汇入",x+12,row(12),0x66b5ff,false);
    g.drawString(font,payload.getCompound("To").hasUUID("Junction")?"目标：路口接入口":landing==LanePoints.Landing.EXACT?"目标：锁定 B":"目标：整条连续同车道",x+12,row(32),0xd2e2ed,false);
    g.drawString(font,"参考半径",x+12,row(134),0xffffff,false);g.drawString(font,"过渡长度",x+17+control/2,row(134),0xffffff,false);
    map(g,x+control+24,row(32),panelWidth-control-36,(int)Math.round(239*uiScale));
    g.enableScissor(x+10,row(283),x+panelWidth-10,y+panelHeight-6);
    g.drawWordWrap(font,Component.literal(pending?status+String.format(Locale.ROOT," 已等待 %.1f 秒。",(System.nanoTime()-pendingSince)/1e9):diagnostic?brief(status):status),x+12,row(283),panelWidth-24,0xffcf8c);
    g.disableScissor();
    previewButton.setTooltip(Tooltip.create(Component.literal(status)));
    super.render(g,mx,my,dt);
  }
}
