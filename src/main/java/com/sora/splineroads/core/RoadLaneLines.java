package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.*;
import java.util.*;
/** Per-road paint overrides. They never change geometry or shared endpoint settings. */
public final class RoadLaneLines {
  public enum Pattern {
    DEFAULT("默认"),NONE("隐藏"),WHITE_SOLID("白实线"),WHITE_DASHED("白虚线"),YELLOW_SOLID("黄实线"),YELLOW_DASHED("黄虚线"),DOUBLE_WHITE("双白实线"),DOUBLE_YELLOW("双黄实线"), LEFT_SOLID_RIGHT_DASHED("左实右虚（白）"), LEFT_DASHED_RIGHT_SOLID("左虚右实（白）"), YELLOW_LEFT_SOLID_RIGHT_DASHED("左实右虚（黄）"), YELLOW_LEFT_DASHED_RIGHT_SOLID("左虚右实（黄）");
    public final String label;Pattern(String s){label=s;}
    public boolean yellow(){return this==YELLOW_SOLID||this==YELLOW_DASHED||this==DOUBLE_YELLOW||this==YELLOW_LEFT_SOLID_RIGHT_DASHED||this==YELLOW_LEFT_DASHED_RIGHT_SOLID;}
    public boolean dashed(){return this==WHITE_DASHED||this==YELLOW_DASHED;}
    public boolean mixed(){return this==LEFT_SOLID_RIGHT_DASHED||this==LEFT_DASHED_RIGHT_SOLID||this==YELLOW_LEFT_SOLID_RIGHT_DASHED||this==YELLOW_LEFT_DASHED_RIGHT_SOLID;}
    public boolean dashedSide(int side){return this==LEFT_SOLID_RIGHT_DASHED||this==YELLOW_LEFT_SOLID_RIGHT_DASHED?side>0:(this==LEFT_DASHED_RIGHT_SOLID||this==YELLOW_LEFT_DASHED_RIGHT_SOLID)&&side<0;}
    public boolean doubled(){return this==DOUBLE_WHITE||this==DOUBLE_YELLOW||mixed();}
  }
  public record Edit(String key,Pattern pattern,double width){
    public Edit {if(key==null||!key.matches("(edge|center|median|shoulder|divider):-?[0-9]{1,2}")||pattern==null||!Double.isFinite(width)||width<.06||width>.4)throw new IllegalArgumentException("线宽须为 0.06–0.4 格");}
  }
  public record Line(String key,String label,double offset){}
  public static List<Line> lines(Mesh m){
    var s=RoadStructures.sample(m,m.length()/2);var l=RoadProfile.layout(m,s);var out=new ArrayList<Line>();
    for(int side:new int[]{-1,1})out.add(new Line("edge:"+side,side<0?"左侧边缘线":"右侧边缘线",side*(s.halfWidth()-.2)));
    if(l.catalog().median()==RoadProfile.Median.DOUBLE_YELLOW&&l.median()<.12){out.add(new Line("center:-1","中央黄线（左）",l.medianCenter()-.14));out.add(new Line("center:1","中央黄线（右）",l.medianCenter()+.14));}
    else if(l.catalog().median()==RoadProfile.Median.DASHED_YELLOW)out.add(new Line("center:0","中央虚线",l.medianCenter()));
    else if(l.catalog().twoWay())for(int side:new int[]{-1,1})out.add(new Line("median:"+side,side<0?"中央隔离左边线":"中央隔离右边线",l.medianCenter()+side*(l.median()/2+.12)));
    var d=l.dividers();for(int i=0;i<d.size();i++)out.add(new Line("divider:"+i,"车道分界线 "+(i+1),d.get(i)));
    for(int side:l.outsideSides())if(l.shoulderWidth()>0||l.cycleWidth()>0)out.add(new Line("shoulder:"+side,side<0?"左侧路肩/非机动车线":"右侧路肩/非机动车线",l.outer(side)));
    out.sort(Comparator.comparingDouble(Line::offset));return List.copyOf(out);
  }
  public static Edit get(Settings s,String key){return s.options().laneLines().stream().filter(e->e.key().equals(key)).findFirst().orElse(new Edit(key,Pattern.DEFAULT,.12));}
  public static List<Edit> with(List<Edit> old,Edit edit){var out=new ArrayList<>(old);out.removeIf(e->e.key().equals(edit.key()));if(edit.pattern()!=Pattern.DEFAULT)out.add(edit);out.sort(Comparator.comparing(Edit::key));return List.copyOf(out);}
  private RoadLaneLines(){}
}
