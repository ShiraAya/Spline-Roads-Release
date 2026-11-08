package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.Mesh;
import com.sora.splineroads.world.*;
import com.sora.splineroads.net.RoadNetwork;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import java.util.concurrent.*;

/** Click the actual motor lane; only the lane changes, never the longitudinal anchor. */
public final class LanePointScreen extends Screen {
  private static final ExecutorService PREVIEW=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"SR lane point view");t.setDaemon(true);return t;});
  private final CompoundTag payload;
  private final RoadRecord road;
  private final Mesh mesh;
  private final LanePoints.Point point;
  private int lane,x,y,w,h,viewX,viewY,viewW,viewH,requestedLane;
  private String status="";
  private Button apply,remove,merge,expand;
  private boolean confirm,pending;
  private LanePointPreview.Image image;
  private CompletableFuture<LanePointPreview.Image> drawing;

  public LanePointScreen(CompoundTag t){
    super(Component.literal("蓝色车道点"));payload=t.copy();road=RoadRecord.load(t.getCompound("Road"));
    point=LaneTopology.point(road,t.getUUID("Point"));lane=point.lane();mesh=road.mesh();
  }
  @Override protected void init(){
    w=Math.min(460,width-16);h=Math.min(310,height-16);x=(width-w)/2;y=(height-h)/2;
    viewX=x+12;viewY=y+36;viewW=w-24;viewH=h-110;
    if(image!=null&&(image.width()!=viewW||image.height()!=viewH))image=null;
    if(drawing!=null){drawing.cancel(false);drawing=null;}
    addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(x+w-56,y+8,44,20).build());
    apply=addRenderableWidget(Button.builder(Component.literal("应用"),b->send(false)).bounds(x+12,y+h-30,88,20).build());
    remove=addRenderableWidget(Button.builder(Component.literal("删除"),b->{
      if(!payload.getList("Dependencies",Tag.TAG_STRING).isEmpty()&&!confirm){minecraft.setScreen(new LaneDeleteConfirmScreen(this,payload.getList("Dependencies",Tag.TAG_STRING),()->{confirm=true;send(true);}));return;}
      send(true);
    }).bounds(x+w-100,y+h-30,88,20).build());
    merge=addRenderableWidget(Button.builder(Component.literal(point.mergeLength()>0?"取消合流缩减":"外侧合流缩减"),b->mergeLane()).bounds(x+104,y+h-30,108,20).build());
    expand=addRenderableWidget(Button.builder(Component.literal(point.mergeLength()<0?"取消外侧扩流":"外侧扩流加道"),b->expandLane()).bounds(x+216,y+h-30,108,20).build());
    buttons();requestView();
  }
  private void buttons(){if(merge!=null)merge.active=point.mergeLength()>=0&&!point.automatic()&&!pending&&lane==point.lane()&&payload.getBoolean("Supported")&&(point.mergeLength()>0||LaneMerge.allowed(mesh,point));if(expand!=null)expand.active=point.mergeLength()<=0&&!point.automatic()&&!pending&&lane==point.lane()&&payload.getBoolean("Supported")&&(point.mergeLength()<0||LaneExpansion.allowed(mesh,point));apply.active=!point.automatic()&&!pending&&lane!=point.lane();remove.active=!point.automatic()&&!pending;}
  private void requestView(){
    if(drawing!=null)return;requestedLane=lane;int chosen=lane,vw=viewW,vh=viewH;
    drawing=CompletableFuture.supplyAsync(()->LanePointPreview.render(mesh,point,vw,vh,chosen),PREVIEW);
  }
  @Override public void tick(){
    super.tick();
    if(payload.getBoolean("PlannerDelete")&&remove!=null){payload.remove("PlannerDelete");if(remove.active)remove.onPress();}
    if(drawing!=null&&drawing.isDone()){
      try{image=drawing.join();}catch(CompletionException e){status="道路视图加载失败："+e.getCause().getMessage();}
      drawing=null;if(requestedLane!=lane)requestView();
    }
  }
  @Override public boolean mouseClicked(double mx,double my,int button){
    if(button==0&&!point.automatic()&&!pending&&image!=null&&mx>=viewX&&my>=viewY&&mx<viewX+viewW&&my<viewY+viewH){
      int selected=image.laneAt((int)(mx-viewX),(int)(my-viewY));
      if(selected>=0){lane=selected;status="";buttons();requestView();}else status="请点击机动车道；隔离带和人行道不能选作车道。";
      return true;
    }
    return super.mouseClicked(mx,my,button);
  }
  private void mergeLane(){
    if(pending||point.automatic())return;
    var t=new CompoundTag();t.putUUID("Id",payload.getUUID("Id"));t.putUUID("Point",point.id());t.putInt("Signature",payload.getInt("Signature"));
    t.putString("Action","lanePoint");t.putDouble("MergeLength",point.mergeLength()>0?0:32);
    pending=true;status="正在提交合流缩减；不创建汇出匝道…";buttons();ClientRoads.sendAction(t);
  }
  private void expandLane(){
    if(pending||point.automatic())return;
    var t=new CompoundTag();t.putUUID("Id",payload.getUUID("Id"));t.putUUID("Point",point.id());t.putInt("Signature",payload.getInt("Signature"));
    t.putString("Action","lanePoint");t.putDouble("MergeLength",point.mergeLength()<0?0:-32);
    pending=true;status="正在提交外侧扩流…";buttons();ClientRoads.sendAction(t);
  }
  private void send(boolean delete){
    if(pending||point.automatic()||!delete&&lane==point.lane())return;
    var t=new CompoundTag();t.putUUID("Id",payload.getUUID("Id"));t.putUUID("Point",payload.getUUID("Point"));t.putInt("Signature",payload.getInt("Signature"));
    t.putString("Action","lanePoint");t.putBoolean("Delete",delete);t.putInt("Lane",lane);
    if(confirm)t.put("ConfirmDependencies",payload.getList("Dependencies",Tag.TAG_STRING).copy());
    pending=true;status="正在提交…";buttons();ClientRoads.sendAction(t);
  }
  public void failed(String s){status=s;pending=false;buttons();}
  @Override public void onClose(){if(drawing!=null)drawing.cancel(false);super.onClose();}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){
    renderBackground(g);g.fill(x,y,x+w,y+h,0xf015222e);
    g.drawString(font,"蓝色车道点 · "+(point.automatic()?"自动尽头":"手动创建"),x+12,y+14,0x66b5ff,false);
    g.fill(viewX-1,viewY-1,viewX+viewW+1,viewY+viewH+1,0xff647a87);
    g.fill(viewX,viewY,viewX+viewW,viewY+viewH,0xff28382e);
    if(image!=null){
      for(int row=0;row<viewH;row++)for(int col=0;col<viewW;){int start=col,color=image.pixels()[row*viewW+col];while(col<viewW&&image.pixels()[row*viewW+col]==color)col++;g.fill(viewX+start,viewY+row,viewX+col,viewY+row+1,color);}
      for(var marker:image.markers())if(marker.lane()==lane){int px=viewX+marker.x(),py=viewY+marker.y();g.fill(px-5,py-5,px+6,py+6,0xffbce7ff);g.fill(px-3,py-3,px+4,py+4,0xff2384ff);}
      int hover=image.laneAt(mx-viewX,my-viewY);
      String label=hover>=0?LanePoints.label(mesh,LanePoints.lane(mesh,point).station(),hover):"道路实景俯视 · 沿路方向 ↑";
      g.fill(viewX+3,viewY+3,viewX+9+font.width(label),viewY+16,0xdd15222e);g.drawString(font,label,viewX+6,viewY+6,0xe7f2f9,false);
    }else g.drawCenteredString(font,"正在加载道路视图…",viewX+viewW/2,viewY+viewH/2,0xcbd9e5);
    int infoY=viewY+viewH+7;
    String selected="所属："+LanePoints.label(mesh,LanePoints.lane(mesh,point).station(),lane)+(LaneSections.active(mesh,LanePoints.lane(mesh,point).station(),lane)?"":"（分离空位）")+"   匝道引用："+payload.getList("Dependencies",Tag.TAG_STRING).size()+" 条";
    g.drawString(font,selected,x+12,infoY,0x99ccff,false);
    String hint=!status.isEmpty()?status:point.automatic()?"自动尽头点只能查看，不能换车道或删除。":payload.getBoolean("Supported")?"点击车道选择；灰色预留槽可用作补入目标，不可从空位汇出。":"可选择车道；此类道路不支持匝道连接器。";
    g.drawWordWrap(font,Component.literal(hint),x+12,infoY+14,w-24,0xffcf8c);
    super.render(g,mx,my,dt);
  }
}
