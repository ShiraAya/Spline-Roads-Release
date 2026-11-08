package com.sora.splineroads.client;

/** Applied before native upload. A single indivisible oversized layer can make
 * progress in an otherwise empty batch; subsequent work waits for another frame. */
final class RoadTerrainUploadBudget {
  static final int BYTES=512*1024,UPLOADS=2;
  private int bytes,uploads;
  private long deadline;
  void begin(){bytes=uploads=0;deadline=0;}
  boolean claim(int size,boolean clock){
    if(uploads>0&&(uploads>=UPLOADS||(long)bytes+size>BYTES||clock&&System.nanoTime()>=deadline))return false;
    if(uploads==0)deadline=System.nanoTime()+750_000;
    bytes+=size;uploads++;return true;
  }
  int bytes(){return bytes;}
  int uploads(){return uploads;}
}
