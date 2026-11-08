package com.sora.splineroads.client;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
/** Paged, exact-ID dependency review shared by point, road and junction deletion. */
public final class LaneDeleteConfirmScreen extends Screen {
 private final Screen parent;private final ListTag ids;private final Runnable confirm;private int page;private boolean seenLast;
 public LaneDeleteConfirmScreen(Screen parent,ListTag ids,Runnable confirm){super(Component.literal("确认级联删除"));this.parent=parent;this.ids=ids.copy();this.confirm=confirm;}
 @Override protected void init(){int x=(width-340)/2,y=(height-242)/2;seenLast|=(page+1)*8>=ids.size();addRenderableWidget(Button.builder(Component.literal("上一页"),b->{page=Math.max(0,page-1);rebuildWidgets();}).bounds(x+12,y+176,95,20).build());addRenderableWidget(Button.builder(Component.literal("下一页"),b->{page=Math.min((ids.size()-1)/8,page+1);rebuildWidgets();}).bounds(x+233,y+176,95,20).build());var yes=addRenderableWidget(Button.builder(Component.literal("确认删除所列依赖"),b->{minecraft.setScreen(parent);confirm.run();}).bounds(x+12,y+210,194,20).build());yes.active=seenLast;addRenderableWidget(Button.builder(Component.literal("取消"),b->onClose()).bounds(x+218,y+210,110,20).build());}
 @Override public void onClose(){minecraft.setScreen(parent);}
 @Override public boolean isPauseScreen(){return false;}
 @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);int x=(width-340)/2,y=(height-242)/2;g.fill(x,y,x+340,y+242,0xf0182430);g.drawString(font,"将连同目标删除以下 "+ids.size()+" 条依赖匝道",x+12,y+12,0xffb39c,false);g.drawString(font,"仅删除清单内的有效连接及其后续分支",x+12,y+32,0xffffff,false);for(int i=0;i<8&&page*8+i<ids.size();i++)g.drawString(font,ids.getString(page*8+i),x+12,y+55+i*14,0x9acbff,false);g.drawString(font,(page+1)+" / "+Math.max(1,(ids.size()+7)/8),x+153,y+182,0xffffff,false);super.render(g,mx,my,dt);}
}
