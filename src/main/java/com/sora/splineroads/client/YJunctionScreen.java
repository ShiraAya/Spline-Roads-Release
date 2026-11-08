package com.sora.splineroads.client;
import com.sora.splineroads.core.*;
import com.sora.splineroads.core.RoadGeometry.*;
import com.sora.splineroads.world.RoadRecord;
import com.sora.splineroads.net.RoadNetwork;
import java.util.*;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.*;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
public final class YJunctionScreen extends Screen {
  private final CompoundTag payload;private EditBox tension;private String status="";private int x,y,w,h;private boolean pending;private Button apply;private YJunctionPlanner.Plan plan;
  private void edited(){ClientRoads.editorChanged(this);pending=false;}
  public YJunctionScreen(CompoundTag t){super(Component.literal("Y 字路口编辑器"));payload=t.copy();}
  @Override protected void init(){w=Math.min(430,width-12);h=Math.min(330,height-8);x=(width-w)/2;y=(height-h)/2;
    for(int i=0;i<3;i++){final int slot=i;var settings=RoadRecord.readSettings(payload.getCompound("Profile"+i));
      var button=addRenderableWidget(Button.builder(Component.literal((char)('A'+i)+" "+RoadLanes.counts(settings).label()),b->{
        payload.putDouble("Tension",parseTension());
        minecraft.setScreen(new RoadLaneConfigScreen(this,RoadProfile.catalog(settings).type(),RoadLanes.counts(settings),(type,counts)->{
          var changed=RoadLanes.configure(settings,type,counts,RoadProfile.layout(settings,settings.width()).laneWidth());
          payload.put("Profile"+slot,RoadRecord.writeSettings(changed));edited();
        },slot==0));
      }).bounds(x+12+i*(w-24)/3,y+31,(w-30)/3,20).build());button.active=!payload.getBoolean("LockedProfile"+i)&&!pending;
    }
    tension=new EditBox(font,x+w-100,y+h-89,86,20,Component.literal("曲线强度"));tension.setMaxLength(5);tension.setValue(Double.toString(payload.getDouble("Tension")));addRenderableWidget(tension);tension.setResponder(v->{edited();preview();});
    apply=addRenderableWidget(Button.builder(Component.literal(payload.hasUUID("Id")?"保存修改":"建造 Y 字路口"),b->send(false)).bounds(x+12,y+h-62,(w-32)/2,20).build());
    addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(x+w/2+4,y+h-62,(w-32)/2,20).build());
    if(payload.hasUUID("Id"))addRenderableWidget(Button.builder(Component.literal("删除此 Y 字路口"),b->send(true)).bounds(x+12,y+h-37,w-24,20).build());preview();
  }
  private double parseTension(){try{return Double.parseDouble(tension.getValue());}catch(RuntimeException e){return payload.getDouble("Tension");}}
  private void preview(){try{var n=payload.getList("Nodes",Tag.TAG_COMPOUND);plan=YJunctionPlanner.plan(RoadRecord.readNode(n.getCompound(0)),RoadRecord.readNode(n.getCompound(1)),RoadRecord.readNode(n.getCompound(2)),RoadRecord.readSettings(payload.getCompound("Profile0")),RoadRecord.readSettings(payload.getCompound("Profile1")),RoadRecord.readSettings(payload.getCompound("Profile2")),Double.parseDouble(tension.getValue()));status="";}catch(IllegalArgumentException e){plan=null;status=e instanceof NumberFormatException?"请输入曲线强度":e.getMessage();}if(apply!=null)apply.active=plan!=null&&!pending;}
  private void send(boolean delete){if(pending||!delete&&plan==null)return;var t=payload.copy();t.putString("Action","yJunction");if(!delete)t.putDouble("Tension",Double.parseDouble(tension.getValue()));t.putBoolean("Delete",delete);pending=true;apply.active=false;ClientRoads.sendAction(t);}
  /** A child settings screen can remain open while this submission completes. */
  void completed(){pending=false;}

  public void failed(String s){pending=false;status=s;apply.active=plan!=null;}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);g.fill(x,y,x+w,y+h,0xf015222e);g.drawString(font,"Y 字路口 · A 双向 / B 出向 / C 入向",x+12,y+12,0xffffff,false);
    g.fill(x+12,y+55,x+w-12,y+h-123,0xff252f36);
    if(plan!=null){var ms=List.of(plan.stem(),plan.outbound(),plan.inbound());double minX=ms.stream().mapToDouble(m->m.min().x()).min().orElse(0),maxX=ms.stream().mapToDouble(m->m.max().x()).max().orElse(1),minZ=ms.stream().mapToDouble(m->m.min().z()).min().orElse(0),maxZ=ms.stream().mapToDouble(m->m.max().z()).max().orElse(1);double scale=Math.min((w-70)/Math.max(1,maxX-minX),(h-198)/Math.max(1,maxZ-minZ));double cx=(minX+maxX)/2,cz=(minZ+maxZ)/2;
      for(int i=0;i<ms.size();i++)for(var s:ms.get(i).samples()){int px=x+w/2+(int)((s.center().x()-cx)*scale),py=y+65+(h-188)/2+(int)((s.center().z()-cz)*scale);int r=Math.max(1,(int)(s.halfWidth()*scale));g.fill(px-r,py-r,px+r+1,py+r+1,i==0?0xff9eabb2:i==1?0xff4bd7a5:0xffeeb268);}
      var n=payload.getList("Nodes",Tag.TAG_COMPOUND);for(int i=0;i<3;i++){var p=RoadRecord.readNode(n.getCompound(i)).position();g.drawCenteredString(font,""+(char)('A'+i),x+w/2+(int)((p.x()-cx)*scale),y+65+(h-188)/2+(int)((p.z()-cz)*scale),0xffffff);}
    }
    g.drawWordWrap(font,Component.literal(status.isEmpty()?"绿色 A→B 按 B 车道过渡，橙色 C→A 按 A 接收方向车道过渡；B、C 须为单向道路，可使用不同车道数。":status),x+14,y+h-116,w-28,status.isEmpty()?0xbdd9d5:0xffaf91);g.drawString(font,"曲线强度（0.2–0.8）",x+14,y+h-82,0xd7e8ef,false);super.render(g,mx,my,dt);
  }
}
