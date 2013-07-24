package com.turn.ttorrent.common;

import javax.xml.bind.annotation.adapters.XmlAdapter;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

public class TrackerAdapter extends XmlAdapter<Trackers, List<List<URI>>> {

  @Override
  public Trackers marshal(List<List<URI>> v) throws Exception {
    Trackers trackers = new Trackers();
    for (List<URI> tracker : v) {
      URIs uris = new URIs();
      for (URI uri : tracker) {
        uris.getUris().add(uri);
      }

      trackers.getTrackers().add(uris);
    }

    return trackers;
  }

  @Override
  public List<List<URI>> unmarshal(Trackers v) throws Exception {
    List<List<URI>> trackers = new ArrayList<List<URI>>();
    for (URIs uris : v.getTrackers()) {
      List<URI> uriList = new ArrayList<URI>();
      for (URI uri : uris.getUris()) {
        uriList.add(uri);
      }

      trackers.add(uriList);
    }

    return trackers;
  }

}