package com.turn.ttorrent.common;

import java.util.ArrayList;
import java.util.List;

public class Trackers {
  List<URIs> trackers = new ArrayList<URIs>();

  public List<URIs> getTrackers() {
    return trackers;
  }

  public void setTrackers(List<URIs> trackers) {
    this.trackers = trackers;
  }
}