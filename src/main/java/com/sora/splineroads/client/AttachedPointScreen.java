package com.sora.splineroads.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;

/** A section marker has no geometry controls. Bezier handles stay in the road editor. */
public final class AttachedPointScreen extends Screen {
  private final CompoundTag payload;
  private Button delete;
  private String status="";
  private int x,y,w;
  public AttachedPointScreen(CompoundTag payload){super(Component.literal(payload.getBoolean("Elevated")?"高架出入点":"道路附属点"));this.payload=payload.copy();}
  @Override protected void init(){
    w=Math.min(300,width-16);x=(width-w)/2;y=Math.max(4,(height-122)/2);
    delete=addRenderableWidget(Button.builder(Component.literal(payload.getBoolean("Elevated")?"删除出入点":"删除附属点"),b->{
      var t=new CompoundTag();t.putString("Action","attachedPoint");t.putUUID("Id",payload.getUUID("Id"));
      t.putUUID("Point",payload.getUUID("Point"));t.putInt("Signature",payload.getInt("Signature"));t.putBoolean("Delete",true);
      ClientRoads.sendAction(t);delete.active=false;
    }).bounds(x+12,y+70,(w-32)/2,20).build());
    addRenderableWidget(Button.builder(Component.literal("关闭"),b->onClose()).bounds(x+20+(w-32)/2,y+70,(w-32)/2,20).build());
  }
  public void failed(String text){status=text;delete.active=true;}
  @Override public void tick(){if(payload.getBoolean("PlannerDelete")&&delete!=null){payload.remove("PlannerDelete");delete.onPress();}}
  @Override public boolean isPauseScreen(){return false;}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){
    renderBackground(g);g.fill(x,y,x+w,y+122,0xf015222e);g.drawString(font,title,x+12,y+12,0xffffff,false);
    g.drawWordWrap(font,Component.literal(payload.getBoolean("Elevated")?"用道路连接器选择此点和高处的尽头端点，设置高架上下坡连接。":"固定在所选道路断面中央，用于标线分段和道路选择。"),x+12,y+34,w-24,0xbbd3dc);
    super.render(g,mx,my,dt);g.drawWordWrap(font,Component.literal(status),x+12,y+98,w-24,0xffbb88);
  }
}
