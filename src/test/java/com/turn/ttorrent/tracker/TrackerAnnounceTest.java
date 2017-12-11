package com.turn.ttorrent.tracker;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.security.NoSuchAlgorithmException;

import static com.turn.ttorrent.tracker.TrackerUtils.loadTorrent;
import static org.testng.Assert.assertEquals;

public class TrackerAnnounceTest {

  private Tracker tracker;

  @BeforeMethod
  public void setUp() throws Exception {
    this.tracker = new Tracker(6969);
    tracker.setAnnounceInterval(5);
    tracker.setPeerCollectorExpireTimeout(10);
    this.tracker.start(false);
  }

  @Test(invocationCount = 5000)
  public void test_announce() throws IOException, NoSuchAlgorithmException {

    assertEquals(0, this.tracker.getTrackedTorrents().size());

    this.tracker.announce(loadTorrent("file1.jar.torrent"));

    assertEquals(1, this.tracker.getTrackedTorrents().size());
  }

  @AfterMethod
  public void tearDown() throws Exception {
    this.tracker.stop();
  }
}
