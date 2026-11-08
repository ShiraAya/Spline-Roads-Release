package com.sora.splineroads.client;

import com.sora.splineroads.core.*;
import com.sora.splineroads.world.RoadRecord;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;

/** Count choices are separate from the server-validated complete road preview. */
public final class ElevatedAccessScreen extends Screen {
  private final CompoundTag payload;private boolean pending;private String status="逐方向分离内侧车道；等车道数时增加外侧直行辅路";
  private final java.util.List<RoadRecord> records;
  private final java.util.List<RoadGeometry.Mesh> meshes;
  private Button build;private int x,y;
  public ElevatedAccessScreen(CompoundTag payload){super(Component.literal("高架出入点 · 道路连接"));this.payload=payload.copy();
    records=this.payload.getList("PlannerPreview",Tag.TAG_COMPOUND).stream().map(t->RoadRecord.load((CompoundTag)t)).toList();
    meshes=records.stream().map(RoadRecord::mesh).toList();
    this.payload.remove("PlannerPreview");}
  private RoadGeometry.Settings settings(){return RoadRecord.readSettings(payload.getCompound("Settings"));}
  @Override protected void init(){x=(width-340)/2;y=Math.max(6,(height-220)/2);var n=RoadLanes.counts(settings());
    label(n.twoWay()?"双向高架":"单向高架 · A → B",x+12,y+38,316);
    label("A → B："+n.forward()+" 车道",x+12,y+64,152);
    label("B → A："+n.reverse()+" 车道",x+176,y+64,152);
    boolean existing=payload.hasUUID("Id"), approved=payload.hasUUID("Token");
    addRenderableWidget(Button.builder(Component.literal(existing?"重新计算连接":"计算完整预览"),b->send("elevatedAccessPreview")).bounds(x+12,y+136,152,20).build());
    build=addRenderableWidget(Button.builder(Component.literal(existing?(approved?"保存连接":"完成"):"建造连接"),b->{
      if(existing&&!approved)onClose();else send("elevatedAccessBuild");
    }).bounds(x+176,y+136,152,20).build());
    build.active=!pending&&(existing||approved);
    var view=addRenderableWidget(Button.builder(Component.literal("实景预览 · P 返回"),b->{show();ManualPlannerClient.worldPreview();minecraft.setScreen(null);}).bounds(x+12,y+162,existing?136:204,20).build());
    view.active=!meshes.isEmpty()&&!pending;
    if(existing)addRenderableWidget(Button.builder(Component.literal("删除连接…"),b->send("deletePreview")).bounds(x+152,y+162,90,20).build());
    addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(x+(existing?246:228),y+162,existing?82:100,20).build());
    if(approved)status="预览已完成，显示高架连接本体；保存时同步更新主路车道";
    else if(existing)status="正在查看已建连接。可直接预览或完成；要更新设施，可重新计算后保存";
    if(!meshes.isEmpty())show();
  }
  private void label(String text,int x,int y,int width){
    addRenderableWidget(Button.builder(Component.literal(text),ignored->{}).bounds(x,y,width,20).build()).active=false;
  }
  private void show(){ClientRoads.nodePreviews=meshes;ClientRoads.preview=meshes.isEmpty()?null:meshes.get(0);ClientRoads.conflictPreview=false;RoadRenderer.previewCandidates(records);}
  private void clearPreview(){ClientRoads.preview=null;ClientRoads.nodePreviews=java.util.List.of();RoadRenderer.previewCandidates(java.util.List.of());}
  @Override public void onClose(){clearPreview();ClientRoads.endEditor(this);ManualPlannerClient.completed(this);super.onClose();}
  private void send(String action){if(pending)return;var t=payload.copy();t.putString("Action",action);pending=true;build.active=false;status="正在检查高架与地面车道、净空和占地…";ClientRoads.sendAction(t);}
  public void failed(String message){pending=false;status=message;if(build!=null)build.active=payload.hasUUID("Token")||payload.hasUUID("Id");}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);g.fill(x,y,x+340,y+210,0xf015222e);g.drawString(font,title,x+12,y+14,0xffffff,false);g.drawWordWrap(font,Component.literal(status),x+12,y+96,316,0xc5dde7);if(payload.getBoolean("Locked"))g.drawString(font,"继承已有高架尽头的方向、车道数和宽度",x+12,y+191,0x93c8d4,false);super.render(g,mx,my,dt);}
}
