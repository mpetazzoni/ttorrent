package com.turn.ttorrent.common;

import junit.framework.TestCase;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;

@Test
public class TorrentTest extends TestCase {

  public void test_create_torrent() throws URISyntaxException, IOException, NoSuchAlgorithmException, InterruptedException {
    URI announceURI = new URI("http://localhost:6969/announce");
    String createdBy = "Test";
    Torrent t = Torrent.create(new File("src/test/resources/parentFiles/file1.jar"), announceURI, createdBy);
    assertEquals(createdBy, t.getCreatedBy());
    assertEquals(announceURI, t.getAnnounceList().get(0).get(0));
  }

  public void load_torrent_created_by_utorrent() throws IOException, NoSuchAlgorithmException, URISyntaxException {
    Torrent t = Torrent.load(new File("src/test/resources/torrents/file1.jar.torrent"), null);
    assertEquals(new URI("http://localhost:6969/announce"), t.getAnnounceList().get(0).get(0));
    assertEquals("B92D38046C76D73948E14C42DF992CAF25489D08", t.getHexInfoHash());
    assertEquals("uTorrent/3130", t.getCreatedBy());
  }
}
