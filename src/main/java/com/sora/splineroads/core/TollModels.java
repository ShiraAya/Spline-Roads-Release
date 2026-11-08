package com.sora.splineroads.core;
import com.sora.splineroads.core.RoadGeometry.V;
import com.sora.splineroads.core.RoadStructures.Part;
import com.sora.splineroads.core.RoadSurface.*;
import java.util.*;

/** Original SR toll equipment, with beveled housings, reflectors and service details. */
public final class TollModels {
  public static boolean supports(Part p){return p.model()!=null&&p.model().startsWith("sr:toll_model_");}
  public static List<Face> faces(Part p){
    var out=new ArrayList<Face>();
    if(p.model().startsWith("sr:toll_model_label_")){
      String text=p.model().substring("sr:toll_model_label_".length());double total=text.length()*.40-.12;
      for(int i=0;i<text.length();i++)letter(out,text.charAt(i),(i*.40-total/2)/p.width(),.28/p.width());
    }else if(p.model().startsWith("sr:toll_model_name_")){
      int color=p.model().endsWith("panel")?0xE3E8E6:p.model().endsWith("sill")?0x39474C:0x9BA8AB;
      double width=p.frameA()==null?p.width():p.frameA().horizontalLength()*2;
      OriginalModels.box(out,-width/(2*p.width()),0,0,width/(2*p.width()),p.height(),1,color);
    }else if(p.model().equals("sr:toll_model_gate")){
      OriginalModels.bevel(out,-.34,0,.12,.34,.10,.88,.07,0x37454D);
      OriginalModels.bevel(out,-.29,.10,.18,.29,1.15,.82,.055,0xE4E9E7);
      OriginalModels.bevel(out,-.30,1.15,.17,.30,1.24,.83,.06,0x294F61);
      OriginalModels.box(out,-.23,.24,.165,.23,.98,.18,0xB8C7CC);
      OriginalModels.box(out,-.17,.83,.15,.12,.9,.165,0x334C57);
      for(double y=.35;y<.69;y+=.08)OriginalModels.box(out,-.16,y,.15,.16,y+.018,.165,0x526673);
      OriginalModels.cylinder(out,new V(-.35,1.04,.5),new V(.35,1.04,.5),.13,.13,12,0x687D88);
      OriginalModels.bevel(out,-.085,1.05,.45,.085,4.42,.57,.025,0xF1F2EA);
      for(double y=1.35;y<4.4;y+=.48)OriginalModels.box(out,-.09,y,.444,.09,y+.22,.576,0xD44736);
      OriginalModels.bevel(out,-.10,4.42,.44,.10,4.5,.58,.025,0x52616C);
      OriginalModels.box(out,.30,.84,.34,.315,1.0,.65,0x31B989);
    }else if(p.model().equals("sr:toll_model_island_nose")){
      var outline=List.of(new V(-.34,0,.05),new V(.34,0,.05),new V(.48,0,.26),new V(.48,0,.94),new V(-.48,0,.94),new V(-.48,0,.26));
      OriginalModels.prism(out,outline,0,.18,0x3C4850);OriginalModels.prism(out,outline,.18,.95,0xE6B830);
      OriginalModels.bevel(out,-.48,.95,.07,.48,1.07,.94,.13,0x303D43);
      for(int side:new int[]{-1,1})for(double z=.28;z<.9;z+=.26)OriginalModels.box(out,side<0?-.484:.478,.27,z,side<0?-.478:.484,.82,z+.12,0x333A3B);
      OriginalModels.box(out,-.25,.30,.042,.25,.76,.052,0x272E32);
      for(int side:new int[]{-1,1})OriginalModels.face(out,List.of(new V(side*.22,.36,.038),new V(side*.04,.53,.038),new V(side*.22,.70,.038)),new V(0,0,-1),0xF6D04B,false,Texture.PLAIN);
      for(int side:new int[]{-1,1}){OriginalModels.cylinder(out,new V(side*.33,1.07,.73),new V(side*.33,1.18,.73),.035,.035,8,0x8B9B9F);OriginalModels.cylinder(out,new V(side*.33,1.18,.73),new V(side*.33,1.25,.73),.06,.06,8,0xECECD9);}
    }else throw new IllegalArgumentException("Unknown toll equipment: "+p.model());
    V along=p.b().sub(p.a());return OriginalModels.transform(out,p.a(),along.horizontalUnit().left().mul(p.width()),new V(0,1,0),along);
  }
  /** Continuous sans-serif outlines, triangulated before extrusion (E/T/C/M are concave). */
  private static void letter(List<Face> out,char ch,double x,double width){
    double[][] shape=switch(ch){
      case 'E' -> new double[][]{{0,0},{1,0},{1,.17},{.22,.17},{.22,.42},{.82,.42},{.82,.59},{.22,.59},{.22,.83},{1,.83},{1,1},{0,1}};
      case 'T' -> new double[][]{{.39,0},{.61,0},{.61,.82},{1,.82},{1,1},{0,1},{0,.82},{.39,.82}};
      case 'C' -> new double[][]{{.18,0},{1,0},{1,.18},{.29,.18},{.22,.25},{.22,.75},{.29,.82},{1,.82},{1,1},{.18,1},{0,.82},{0,.18}};
      case 'M' -> new double[][]{{0,0},{.20,0},{.20,.68},{.50,.36},{.80,.68},{.80,0},{1,0},{1,1},{.79,1},{.50,.65},{.21,1},{0,1}};
      default -> throw new IllegalArgumentException("Unknown toll letter "+ch);
    };
    var outline=Arrays.stream(shape).map(v->new V(x+v[0]*width,0,v[1]*.48)).toList();
    for(var triangle:JunctionPaint.triangulate(outline))for(int side:new int[]{0,1})OriginalModels.face(out,triangle.stream().map(v->new V(v.x(),v.z(),side)).toList(),new V(0,0,side==0?-1:1),0xFAFAEE,false,Texture.PLAIN);
    for(int i=0;i<outline.size();i++){var a=outline.get(i);var b=outline.get((i+1)%outline.size());OriginalModels.face(out,List.of(new V(a.x(),a.z(),0),new V(b.x(),b.z(),0),new V(b.x(),b.z(),1),new V(a.x(),a.z(),1)),new V(b.z()-a.z(),a.x()-b.x(),0),0xCCD6D3,false,Texture.PLAIN);}
  }
  private TollModels(){}
}
