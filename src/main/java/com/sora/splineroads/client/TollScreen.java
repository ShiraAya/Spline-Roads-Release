package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.TollPlaza.*;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import com.mojang.blaze3d.vertex.*;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/** Closing preserves the world preview; every parameter edit invalidates its commit token. */
public final class TollScreen extends Screen {
  private static CompoundTag draft;private static TollScreen waiting;private static long requests;
  private final CompoundTag payload;private final List<Runnable> captures=new ArrayList<>();private final List<Label> labels=new ArrayList<>();
  private record Label(String text,int x,int y){}
  private int page,left,top,panel,previewDelay=8;private double uiScale=1;private List<RoadSurface.Face> mapFaces=List.of();private boolean initializing,pending,awaitingPreview,deleteConfirm;private Button build,preview,calculate;private String status="正在准备俯视预览…";private UUID token;private long request;
  private RoadPreviewData.Toll checked;
  private double mapMinX,mapMinZ,mapMaxX,mapMaxZ;
  public TollScreen(CompoundTag t){super(Component.literal("Spline Roads · 收费站"));payload=t.copy();payload.remove("Token");if(!inline())TollCodec.standaloneMode(payload.getCompound("Spec"),payload.getCompound("Spec").getBoolean("TwoWay"));request=++requests;}
  public static void clear(){
    var mc=Minecraft.getInstance();TollScreen owner=waiting!=null?waiting:mc.screen instanceof TollScreen screen?screen:null;
    if(waiting!=null&&(waiting.token!=null||waiting.pending))waiting.cancelPreview();
    boolean owned=owner!=null&&ClientRoads.endEditor(owner);if(owner!=null){owner.pending=false;owner.awaitingPreview=false;}draft=null;waiting=null;++requests;
    if(owned){clearPublished();if(mc.screen==owner)mc.setScreen(null);}
  }
  private static void clearPublished(){TollPreview.clear();JunctionScreen.previewMesh=null;JunctionScreen.previewFaces=List.of();ClientRoads.preview=null;ClientRoads.nodePreviews=List.of();}
  private static void resume(TollScreen screen){ClientRoads.beginEditor(screen);screen.showWorld();Minecraft.getInstance().setScreen(screen);}
  private void showWorld(){if(!ClientRoads.ownsPreview(this))return;ClientRoads.conflictPreview=false;if(checked==null){clearPublished();return;}TollPreview.show(TollCodec.read(payload.getCompound("Spec")),checked.plan());ClientRoads.preview=checked.mesh();ClientRoads.nodePreviews=checked.roads();JunctionScreen.previewMesh=checked.mesh();JunctionScreen.previewFaces=checked.faces();}
  public static void open(CompoundTag fresh){open(fresh,null);}
  public static void open(CompoundTag fresh,RoadPreviewData prepared){
    String kind=fresh.getString("Kind");if(kind.equals("tollReset")){
      var screen=waiting!=null?waiting:Minecraft.getInstance().screen instanceof TollScreen current?current:null;
      if(fresh.contains("Request")&&(screen==null||screen.request!=fresh.getLong("Request")))return;
      clear();return;
    }
    if(kind.equals("tollCheck")){if(waiting!=null)waiting.checked(fresh,prepared);return;}
    if(kind.equals("tollResume")){if(waiting!=null)resume(waiting);else if(draft!=null)resume(new TollScreen(draft));return;}
    // Keep the exact validated draft/token when returning from a world preview.
    // The server still validates snapshots and token lifetime at commit.
    if(waiting!=null&&!fresh.getBoolean("DeleteOnly")&&sameSelection(fresh,waiting.payload)){
      resume(waiting);return;
    }
    if(waiting!=null){waiting.cancelPreview();if(ClientRoads.ownsPreview(waiting))clearPublished();waiting.request=++requests;waiting=null;}
    var t=fresh.copy();
    if(draft!=null&&!t.getBoolean("DeleteOnly")&&sameSelection(t,draft)){
      var current=TollCodec.read(t.getCompound("Spec"));var previous=TollCodec.read(draft.getCompound("Spec"));t.put("Spec",TollCodec.write(new Spec(current.a(),current.b(),previous.config(),current.portA(),current.portB(),current.path())));t.putString("Adjust",draft.getString("Adjust"));
    }
    Minecraft.getInstance().setScreen(new TollScreen(t));
  }
  private static boolean sameSelection(CompoundTag a,CompoundTag b){
    return Objects.equals(a.get("InlineRoad"),b.get("InlineRoad"))&&a.getDouble("InlineStation")==b.getDouble("InlineStation")&&a.getInt("InlineSignature")==b.getInt("InlineSignature")&&Arrays.equals(a.getLongArray("Points"),b.getLongArray("Points"))&&Objects.equals(a.hasUUID("Id")?a.getUUID("Id"):null,b.hasUUID("Id")?b.getUUID("Id"):null);
  }
  private void remember(){if(!payload.getBoolean("DeleteOnly"))draft=payload.copy();}
  private void cancelPreview(){awaitingPreview=false;if(Minecraft.getInstance().getConnection()!=null){var t=new CompoundTag();t.putString("Action","tollCancelPreview");t.putLong("Request",request);if(token!=null)t.putUUID("Token",token);ClientRoads.sendAction(t);}}
  private void changed(){if(initializing||pending&&!awaitingPreview)return;ClientRoads.editorChanged(this);if(token!=null||pending)cancelPreview();token=null;checked=null;pending=false;awaitingPreview=false;request=++requests;status="参数已改变，请点击计算更新俯视预览";previewDelay=0;mapFaces=List.of();if(build!=null)build.active=false;if(preview!=null)preview.active=false;if(calculate!=null)calculate.active=true;if(ClientRoads.ownsPreview(this))clearPublished();}
  /** EditBox also reports cursor movement; only text changes invalidate the receipt. */
  private void watch(EditBox box){String[] previous={box.getValue()};box.setResponder(value->{if(!value.equals(previous[0])){previous[0]=value;changed();}});}
  private boolean capture(){if(pending&&!awaitingPreview)return true;try{captures.forEach(Runnable::run);TollCodec.read(payload.getCompound("Spec"));remember();return true;}catch(IllegalArgumentException e){status=e.getMessage();token=null;return false;}}
  private boolean inline(){return payload.hasUUID("InlineRoad")||payload.getCompound("Spec").contains("Path");}
  private Button button(String label,int x,int y,int w,Runnable action){return addRenderableWidget(Button.builder(Component.literal(label),b->action.run()).bounds(x,y,w,Math.max(14,(int)Math.round(20*uiScale))).build());}
  private void change(Runnable action){if(pending&&!awaitingPreview)return;if(capture()){action.run();changed();remember();rebuildWidgets();}}
  private void field(String name,CompoundTag t,String key,int col,int row,boolean integer){
    int w=(panel-30)/2,x=left+10+col*(w+10),y=row(56+row*38);labels.add(new Label(name,x,y));var box=new EditBox(font,x,y+10,w,Math.max(12,(int)Math.round(18*uiScale)),Component.literal(name));box.setMaxLength(8);box.setValue(integer?Integer.toString(t.getInt(key)):String.format(Locale.ROOT,"%.2f",t.getDouble(key)));watch(box);addRenderableWidget(box);
    if(inline()&&Set.of("A","B","LaneWidth").contains(key))box.setEditable(false);
    captures.add(()->{try{if(integer)t.putInt(key,Integer.parseInt(box.getValue()));else t.putDouble(key,Double.parseDouble(box.getValue()));}catch(NumberFormatException e){throw new IllegalArgumentException(name+"：请输入有效数字");}});
  }
  @Override protected void init(){
    initializing=true;captures.clear();labels.clear();panel=Math.min(330,(width-48)/2);left=12;top=8;uiScale=Math.min(1,(height-20)/340.0);int half=(panel-30)/2,x=left+10;
    String[] tabs={"模式/端点",payload.getCompound("Spec").getBoolean("TwoWay")?"A→B 参数":"单向参数",payload.getCompound("Spec").getBoolean("TwoWay")?"B→A 参数":"双向预设","尺寸"};for(int i=0;i<4;i++){int next=i;button((page==i?"● ":"")+tabs[i],x+i*(panel-20)/4,row(26),(panel-26)/4,()->{if(capture()){page=next;rebuildWidgets();}});}
    var s=payload.getCompound("Spec");
    if(page==0){
      button("模式："+(s.getBoolean("TwoWay")?"双向":"单向"),x,row(60),half,()->change(()->TollCodec.standaloneMode(s,!s.getBoolean("TwoWay")))).active=!inline();
      var reverse=button("单向："+(s.getBoolean("Reverse")?"B→A":"A→B"),x+half+10,row(60),half,()->change(()->s.putBoolean("Reverse",!s.getBoolean("Reverse"))));reverse.active=!s.getBoolean("TwoWay")&&!inline();
      button(s.getBoolean("LeftTraffic")?"左侧通行":"右侧通行",x,row(86),panel-20,()->change(()->s.putBoolean("LeftTraffic",!s.getBoolean("LeftTraffic")))).active=!inline();
      button(inline()?"原路断面接口固定":"调整："+adjustLabel(payload.getString("Adjust")),x,row(112),panel-20,()->change(()->{var values=Adjust.values();payload.putString("Adjust",values[(Adjust.valueOf(payload.getString("Adjust")).ordinal()+1)%values.length].name());})).active=!inline();
      int nx=x;labels.add(new Label("收费站名称（选填）",nx,row(138)));
      var name=new EditBox(font,nx,row(150),panel-20,Math.max(14,(int)Math.round(20*uiScale)),Component.literal("收费站名称（选填，默认空）"));name.setMaxLength(80);name.setValue(s.getString("Name"));watch(name);addRenderableWidget(name);captures.add(()->s.putString("Name",name.getValue()));
    }else if(page==1||page==2){
      var d=s.getCompound(page==1?"Forward":"Backward");field("A 端车道数 1–7",d,"A",0,0,true);field("B 端车道数 1–7",d,"B",1,0,true);field("ETC 通道（合计 1–7）",d,"ETC",0,1,true);field("人工通道（合计 1–7）",d,"Manual",1,1,true);
      labels.add(new Label(page==2&&!s.getBoolean("TwoWay")?"单向只用第一组数量；反转保持 A/B 配置归属":"每个单元对应一条收费通道；两向分别保存",x,row(140)));
    }else{
      field("接口车道宽 2–5",s,"LaneWidth",0,0,false);field("收费通道净宽 4–7",s,"PassageWidth",1,0,false);field("最低净高 6–10",s,"ClearHeight",0,1,false);
      button("结构："+s.getString("Structure"),x+half+10,row(101),half,()->change(()->s.putString("Structure",switch(s.getString("Structure")){case "AUTO"->"GROUND";case "GROUND"->"BRIDGE";default->"AUTO";})));
    }
    int foot=height-54,third=(panel-28)/3;
    calculate=button("计算",x,foot,third,()->requestPreview());calculate.active=!pending&&!payload.getBoolean("DeleteOnly");
    preview=button("实景预览",x+third+4,foot,third,()->preview());preview.active=token!=null&&checked!=null&&!pending;
    build=button(payload.hasUUID("Id")?"更新收费站":"建造收费站",x+2*(third+4),foot,third,()->submit("tollBuild"));build.active=token!=null&&checked!=null&&!pending;
    button("关闭",x,height-30,third,()->onClose());
    if(payload.hasUUID("Id"))button(deleteConfirm?"确认删除收费站":"删除收费站",x+third+4,height-30,2*third+4,()->{if(!deleteConfirm){deleteConfirm=true;rebuildWidgets();}else submit("tollDelete");});
    if(payload.getBoolean("DeleteOnly")){preview.active=false;build.active=false;}
    initializing=false;
  }
  private static String adjustLabel(String s){return switch(s){case "AB"->"AB 两端等量延长";case "A"->"仅移动 A";case "B"->"仅移动 B";default->"固定 A/B";};}
  private int row(int offset){return top+(int)Math.round(offset*uiScale);}
  @Override public void tick(){if(previewDelay>0&&--previewDelay==0&&!payload.getBoolean("DeleteOnly"))requestPreview();}
  private void preview(){if(pending||checked==null||token==null)return;showWorld();ManualPlannerClient.worldPreview();Minecraft.getInstance().setScreen(null);}
  private void requestPreview(){if(pending||!capture())return;previewDelay=0;ClientRoads.editorChanged(this);try{
    token=null;pending=true;awaitingPreview=true;waiting=this;request=++requests;status="正在计算并校验地形、端点与外接道路…";build.active=false;preview.active=false;calculate.active=false;
    var t=TollInputs.command(payload);t.putString("Action","tollPreview");t.putLong("Request",request);ClientRoads.sendAction(t);
  }catch(IllegalArgumentException e){pending=false;awaitingPreview=false;status=e.getMessage();token=null;build.active=false;calculate.active=true;}}
  private static List<RoadSurface.Face> faces(TollPlanner.Plan p){var out=new ArrayList<>(p.paint());for(var part:p.structures())out.addAll(part.faces());return List.copyOf(out);}
  private void checked(CompoundTag t,RoadPreviewData prepared){if(t.getLong("Request")!=request||!awaitingPreview)return;pending=false;awaitingPreview=false;if(calculate!=null)calculate.active=true;
    if(t.contains("Error")){if(preview!=null)preview.active=false;status=t.getString("Error");token=null;checked=null;if(build!=null)build.active=false;if(ClientRoads.ownsPreview(this)){clearPublished();Minecraft.getInstance().setScreen(this);}return;}
    var result=t.getCompound("Inputs");token=result.getUUID("Token");checked=prepared==null?RoadPreviewData.preparePayload(t).toll():prepared.toll();prepareMap();showWorld();if(preview!=null)preview.setMessage(Component.literal("实景预览"));
    status=String.format(Locale.ROOT,"可建造 · A 移动 %.1f / B 移动 %.1f 格 · 联动外接道路 %d 条",result.getDouble("MovedA"),result.getDouble("MovedB"),result.getInt("Affected"));if(build!=null)build.active=true;if(preview!=null)preview.active=true;remember();if(ClientRoads.ownsPreview(this)&&Minecraft.getInstance().screen==null&&Minecraft.getInstance().player!=null)Minecraft.getInstance().player.displayClientMessage(Component.literal("收费站预览已就绪，可返回设置建造"),true);
  }
  private void submit(String action){if(pending||!capture())return;if(action.equals("tollBuild")&&(token==null||checked==null))return;var t=TollInputs.command(payload);t.putString("Action",action);if(token!=null)t.putUUID("Token",token);pending=true;awaitingPreview=false;waiting=this;build.active=false;preview.active=false;calculate.active=false;ClientRoads.sendAction(t);}
  public static void result(TollScreen owner,boolean success,String message){if(waiting!=owner||!owner.pending)return;var screen=waiting;screen.pending=false;screen.awaitingPreview=false;screen.status=message;if(success){ManualPlannerClient.completed(owner);clear();}else{screen.token=null;if(screen.build!=null)screen.build.active=false;if(screen.preview!=null)screen.preview.active=false;if(screen.calculate!=null)screen.calculate.active=true;}}
  static void deliveryRecovery(){if(waiting!=null&&waiting.pending&&waiting.awaitingPreview){var screen=waiting;screen.cancelPreview();screen.pending=false;screen.awaitingPreview=false;screen.checked=null;screen.request=++requests;screen.status="预览传输繁忙，请重新预览";if(screen.preview!=null)screen.preview.active=false;if(screen.calculate!=null)screen.calculate.active=true;if(screen.build!=null)screen.build.active=false;if(ClientRoads.ownsPreview(screen))clearPublished();}}
  @Override public void onClose(){capture();Minecraft.getInstance().setScreen(null);}
  @Override public boolean isPauseScreen(){return false;}
  private void prepareMap(){
    mapMinX=mapMinZ=Double.POSITIVE_INFINITY;mapMaxX=mapMaxZ=Double.NEGATIVE_INFINITY;
    for(var sample:checked.mesh().samples())for(double side:new double[]{-1,1}){var p=sample.at(side*sample.halfWidth(),0);mapMinX=Math.min(mapMinX,p.x());mapMinZ=Math.min(mapMinZ,p.z());mapMaxX=Math.max(mapMaxX,p.x());mapMaxZ=Math.max(mapMaxZ,p.z());}
    var faces=new ArrayList<RoadSurface.Face>();var roads=new ArrayList<>(checked.roads());if(!roads.contains(checked.mesh()))roads.add(checked.mesh());
    for(var road:roads)for(int i=1;i<road.samples().size();i++){var a=road.samples().get(i-1);var b=road.samples().get(i);faces.add(new RoadSurface.Face(List.of(a.at(a.halfWidth(),0),a.at(-a.halfWidth(),0),b.at(-b.halfWidth(),0),b.at(b.halfWidth(),0)),0x637885));}
    faces.addAll(checked.faces());faces.sort(Comparator.comparingDouble(f->f.points().stream().mapToDouble(RoadGeometry.V::y).average().orElse(0)));mapFaces=List.copyOf(faces);
  }
  private void map(GuiGraphics g){
    int x=left+panel+12,y=top+26,w=width-x-12,h=height-y-12;g.fill(x,y,x+w,y+h,0xff09161f);
    g.drawString(font,"北 ↑",x+w-36,y+8,0xffffff,false);
    g.drawString(font,checked!=null?"俯视预览":pending?"计算与校验中…":"等待有效参数",x+8,y+8,0x9ed7e6,false);
    if(checked==null)return;
    var mesh=checked.mesh();double dx=Math.max(1,mapMaxX-mapMinX),dz=Math.max(1,mapMaxZ-mapMinZ);
    double scale=Math.min((w-24)/dx,(h-50)/dz),ox=x+(w-dx*scale)/2,oz=y+28+(h-50-dz*scale)/2;
    g.enableScissor(x+2,y+23,x+w-2,y+h-2);g.flush();RenderSystem.disableCull();RenderSystem.enableBlend();RenderSystem.defaultBlendFunc();RenderSystem.setShader(GameRenderer::getPositionColorShader);
    var buffer=Tesselator.getInstance().getBuilder();buffer.begin(VertexFormat.Mode.TRIANGLES,DefaultVertexFormat.POSITION_COLOR);var pose=g.pose().last().pose();
    for(var f:mapFaces){var points=f.points();int c=f.color();for(int i=1;i+1<points.size();i++)for(var v:List.of(points.get(0),points.get(i),points.get(i+1)))buffer.vertex(pose,(float)(ox+(v.x()-mapMinX)*scale),(float)(oz+(v.z()-mapMinZ)*scale),0).color((c>>16)&255,(c>>8)&255,c&255,255).endVertex();}
    BufferUploader.drawWithShader(buffer.end());RenderSystem.disableBlend();RenderSystem.enableCull();
    for(int i=0;i<2;i++){var v=(i==0?mesh.first():mesh.last()).center();String label=i==0?"A":"B";int px=(int)(ox+(v.x()-mapMinX)*scale),pz=(int)(oz+(v.z()-mapMinZ)*scale);g.drawString(font,label,Math.max(x+4,Math.min(x+w-font.width(label)-4,px)),Math.max(y+25,Math.min(y+h-font.lineHeight-4,pz)),0xffffff,false);}g.disableScissor();
  }
  @Override public void render(GuiGraphics g,int mx,int my,float dt){
    renderBackground(g);g.fill(left,top,width-12,height-8,0xEC172128);g.drawString(font,"SR · 收费站",left+10,top+9,0xFFFFFF);map(g);super.render(g,mx,my,dt);for(var l:labels)g.drawString(font,l.text(),l.x(),l.y(),0xDCE5E8);
    try{var spec=TollCodec.read(payload.getCompound("Spec"));var n=TollPlanner.lengths(spec);g.drawString(font,font.plainSubstrByWidth(String.format(Locale.ROOT,"当前 %.1f / 最低 %.1f / 尚缺 %.1f 格",n.current(),n.minimum(),n.deficit()),panel-20),left+10,row(185),n.deficit()>1e-5?0xFFBB77:0xA7E4B1);g.drawString(font,font.plainSubstrByWidth("收费单元："+spec.config().forward().units()+(spec.config().twoWay()?" + "+spec.config().backward().units():"")+" · "+adjustLabel(payload.getString("Adjust")),panel-20),left+10,row(200),0xC1D2DA);
    }catch(IllegalArgumentException e){token=null;if(build!=null)build.active=false;}
    g.enableScissor(left+8,row(222),left+panel-8,height-60);g.drawWordWrap(font,Component.literal(status),left+10,row(222),panel-20,0xFFE5B1);g.disableScissor();
  }
}
