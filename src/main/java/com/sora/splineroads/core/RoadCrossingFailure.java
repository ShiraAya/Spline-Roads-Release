package com.sora.splineroads.core;

import com.sora.splineroads.core.RoadGeometry.Mesh;
import com.sora.splineroads.core.RoadGeometry.V;
import java.util.Locale;
import java.util.UUID;

/** A crossing rejection belongs to the road being planned, which may be a dependency. */
public final class RoadCrossingFailure extends IllegalArgumentException {
  /** A terrain obstruction depends on the chosen vertical candidate. */
  public static final class GeometryRejection extends IllegalArgumentException {
    public GeometryRejection(String message){super(message);}
  }
  private final UUID roadId;
  private final String historyReason;
  private final String diagnostic;
  private final V failurePosition,roadStart,roadEnd;

  public RoadCrossingFailure(UUID roadId,Mesh mesh,String historyReason,IllegalArgumentException cause){
    super(message(roadId,mesh,cause),cause);
    this.roadId=roadId;
    this.historyReason=historyReason;
    roadStart=mesh.first().center();roadEnd=mesh.last().center();failurePosition=position(mesh,cause);
    var start=roadStart;var end=roadEnd;
    String detail="";
    if(cause instanceof RoadsideBypass.ValidationException failure){
      detail=String.format(Locale.ROOT," reason=%s side=%d stationFrom=%.6f stationTo=%.6f slope=%s limit=%.6f",
          failure.reason(),failure.side(),failure.stationFrom(),failure.stationTo(),
          Double.toString(failure.slope()),failure.limit());
      if(failure.worldFrom()!=null&&failure.worldTo()!=null)
        detail+=" worldFrom="+coordinates(failure.worldFrom(),6)+" worldTo="+coordinates(failure.worldTo(),6);
    }
    diagnostic=String.format(Locale.ROOT,"Cycle crossing rejected road=%s history=%s start=(%.3f,%.3f,%.3f) end=(%.3f,%.3f,%.3f) failurePosition=%s%s",
        roadId,historyReason,start.x(),start.y(),start.z(),end.x(),end.y(),end.z(),coordinates(failurePosition,6),detail);
  }
  private static String message(UUID roadId,Mesh mesh,IllegalArgumentException cause){
    String location="非机动车道所在道路 "+roadId;
    if(cause instanceof RoadsideBypass.ValidationException failure){
      location+=String.format(Locale.ROOT,"，%s（按 A→B），距起点 %.2f–%.2f 格",
          failure.side()<0?"左侧":"右侧",failure.stationFrom(),failure.stationTo());
      if(Double.isFinite(failure.slope()))location+=String.format(Locale.ROOT,"，实际坡度 %s%% / 上限 %.0f%%（取引道最大值）",percent(failure.slope(),failure.limit()),failure.limit()*100);
      else if(Double.isInfinite(failure.slope()))location+="，存在垂直高差";
    }
    location+="，问题坐标 "+coordinates(position(mesh,cause),2)+"；道路起点 A"+coordinates(mesh.first().center(),2)
        +" → 终点 B"+coordinates(mesh.last().center(),2);
    return location+"；"+(cause.getMessage()==null?"非机动车道规划失败":cause.getMessage());
  }
  private static String percent(double slope,double limit){
    for(int digits=2;digits<=6;digits++){
      var text=String.format(Locale.ROOT,"%."+digits+"f",slope*100);
      if(slope<=limit||Double.parseDouble(text)>limit*100)return text;
    }
    return Double.toString(slope*100);
  }
  private static V position(Mesh mesh,IllegalArgumentException cause){
    if(cause instanceof RoadsideBypass.ValidationException failure){
      if(failure.worldFrom()!=null&&failure.worldTo()!=null)return failure.worldFrom().add(failure.worldTo()).mul(.5);
      double station=(failure.stationFrom()+failure.stationTo())/2;
      if(Double.isFinite(station))return RoadStructures.sample(mesh,Math.max(0,Math.min(mesh.length(),station))).center();
    }
    return mesh.first().center();
  }
  private static String coordinates(V point,int digits){
    return String.format(Locale.ROOT,"(X=%."+digits+"f, Y=%."+digits+"f, Z=%."+digits+"f)",point.x(),point.y(),point.z());
  }
  public UUID roadId(){return roadId;}
  public String historyReason(){return historyReason;}
  public V failurePosition(){return failurePosition;}
  public V roadStart(){return roadStart;}
  public V roadEnd(){return roadEnd;}
  /** Bounded structured context for the authoritative action log; no world or player dump. */
  public String diagnostic(){return diagnostic;}
  /** Resource limits and unrelated action errors must never trigger a retry. */
  public boolean candidateDependent(){return getCause() instanceof RoadsideBypass.ValidationException||getCause() instanceof GeometryRejection;}
  public static RoadCrossingFailure find(Throwable failure){
    for(int i=0;failure!=null&&i<8;i++,failure=failure.getCause())
      if(failure instanceof RoadCrossingFailure crossing)return crossing;
    return null;
  }
}
