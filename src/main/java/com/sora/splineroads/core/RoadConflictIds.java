package com.sora.splineroads.core;

import java.util.*;
import java.util.regex.Pattern;

/** Internal road identities remain machine-readable; players see scene references. */
public final class RoadConflictIds {
  private static final Pattern ID=Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
  public static List<UUID> read(String message){
    var ids=new LinkedHashSet<UUID>();var matcher=ID.matcher(message==null?"":message);
    while(matcher.find())ids.add(UUID.fromString(matcher.group()));return List.copyOf(ids);
  }
  public static String display(String message){
    if(message==null)return "匝道规划失败";
    String text=message;int n=0;
    for(var id:read(message))text=text.replaceAll("(?i)"+id,"【红色道路 "+(++n)+"】");
    return text;
  }
  private RoadConflictIds(){}
}
