package com.sora.splineroads.world;
import java.util.*;import net.minecraft.server.level.ServerPlayer;
public final class Third211Scenes {
 public static void rebuildSeam(net.minecraft.server.level.ServerLevel l){var d=RoadData.get(l);var r=d.index.roads.get(UUID.fromString("6e9458be-f710-418c-896d-af0378a68024")).record;d.connect(l,null,r.a(),r.b(),r.settings(),r.id());}
 public static void openSpui(ServerPlayer p){p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND,new net.minecraft.world.item.ItemStack(com.sora.splineroads.SplineRoads.INTERCHANGE.get()));var id=UUID.fromString("c3ba2c13-3ca2-3977-9e62-80fbd51280c3");var t=RoadData.get(p.serverLevel()).interchanges.get(id);com.sora.splineroads.net.RoadNetwork.open(p,Interchanges.payload(p.serverLevel(),p,t.getLongArray("Points"),id));}
}
