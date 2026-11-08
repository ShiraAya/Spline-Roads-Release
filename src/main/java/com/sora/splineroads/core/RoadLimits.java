package com.sora.splineroads.core;

/** Shared limits for preview, authoritative construction and persisted alignments. */
public final class RoadLimits {
  public static final int MAX_ENDPOINT_DISTANCE = 2048;
  public static final int MAX_PATH_LENGTH = 4096;
  public static final int MAX_SAMPLES = 16384;
  public static final int MAX_BODY_CELLS = 262144;
  public static final int MAX_EDIT_CELLS = 4_000_000;
  public static final int MAX_MULTI_INTERCHANGE_EDIT_CELLS = 8_000_000;
  public static final int MAX_MULTI_INTERCHANGE_SCAN_CELLS = 24_000_000;
  public static final int MAX_WORK_CHUNKS = 2048;
  public static final int MAX_MULTI_INTERCHANGE_WORK_CHUNKS = 4096;
  public static double maximumWidth(RoadGeometry.Style style){
    if(style==RoadGeometry.Style.TOLL)return 128;
    var type=RoadProfile.catalog(style).type();
    return type==RoadProfile.Type.ORDINARY||type==RoadProfile.Type.HIGHWAY?96:64;
  }

  private RoadLimits() {}
}
