package com.sora.splineroads.client;
import com.sora.splineroads.core.RoadSlicing;
import com.sora.splineroads.config.RoadClientConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
/** Intentionally only the connection choice; road properties remain in the road editor. */
public final class RoadSplitScreen extends Screen {
  public RoadSplitScreen(){super(Component.literal("道路分割器"));}
  @Override protected void init(){int x=(width-220)/2,y=(height-94)/2;for(int i=0;i<2;i++){var mode=RoadSlicing.Connection.values()[i];addRenderableWidget(Button.builder(Component.literal((RoadClientConfig.SPLIT_CONNECTION.get()==mode?"✓ ":"")+(i==0?"连续":"路口")),button->{RoadClientConfig.SPLIT_CONNECTION.set(mode);RoadClientConfig.SPEC.save();RoadSplitterClient.clear();onClose();}).bounds(x+10+i*104,y+40,96,20).build());}}
  @Override public void render(GuiGraphics g,int mx,int my,float dt){renderBackground(g);int x=(width-220)/2,y=(height-94)/2;g.fill(x,y,x+220,y+94,0xf012212c);g.drawCenteredString(font,title,width/2,y+13,0xffffff);g.drawCenteredString(font,"瞄准道路预览 · 右键分割 · Shift取消",width/2,y+73,0xbad0d9);super.render(g,mx,my,dt);}
  @Override public boolean isPauseScreen(){return false;}
}
