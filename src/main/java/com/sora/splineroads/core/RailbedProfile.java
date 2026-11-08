package com.sora.splineroads.core;

import java.util.List;

/** A road section's railway-specific inputs. MTR owns the paths; SR owns only the bed. */
public record RailbedProfile(List<RailbedPath.Path> paths, double requestedWidth, RailbedOptions fittings) {
  public static final RailbedProfile NONE=new RailbedProfile(List.of(),0,RailbedOptions.DEFAULT);
  public RailbedProfile {
    paths=List.copyOf(paths);
    if(paths.size()>RailbedGeometry.MAX_TRACKS||!Double.isFinite(requestedWidth)||requestedWidth<0||requestedWidth>64||fittings==null)
      throw new IllegalArgumentException("无效的铁路断面设置");
  }
  public boolean active(){return !paths.isEmpty();}
}
