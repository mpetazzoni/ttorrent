package com.turn.ttorrent.tracker;

import com.turn.ttorrent.ClientFactory;
import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.Utils;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Torrent;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.*;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static com.turn.ttorrent.tracker.TrackerUtils.TEST_RESOURCES;
import static org.testng.Assert.*;

@Test
public class TrackerTest {

  private Tracker tracker;
  private TempFiles tempFiles;
  //  private String myLogfile;
  private List<Client> clientList = new ArrayList<Client>();

  private final ClientFactory clientFactory;


  public TrackerTest() {
    clientFactory = new ClientFactory();
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS}] %6p - %20.20c - %m %n")));
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    Torrent.setHashingThreadsCount(1);
  }

  @BeforeMethod
  protected void setUp() throws Exception {
    tempFiles = new TempFiles();
    Torrent.setHashingThreadsCount(1);
    startTracker();
  }

  public void test_tracker_all_ports() throws IOException {
    final int port = tracker.getAnnounceURI().getPort();
    final Enumeration<NetworkInterface> e = NetworkInterface.getNetworkInterfaces();
    while (e.hasMoreElements()) {
      final NetworkInterface ni = e.nextElement();
      final Enumeration<InetAddress> addresses = ni.getInetAddresses();
      while (addresses.hasMoreElements()) {
        final InetAddress addr = addresses.nextElement();
        try {
          Socket s = new Socket(addr, port);
          s.close();
        } catch (Exception ex) {
          if (System.getProperty("java.version").startsWith("1.7.") || addr instanceof Inet4Address) {
            fail("Unable to connect to " + addr, ex);
          }
        }
      }

    }
  }

  public void test_share_and_download() throws IOException, NoSuchAlgorithmException, InterruptedException {
    final TrackedTorrent tt = this.tracker.announce(loadTorrent("file1.jar.torrent"));
    assertEquals(0, tt.getPeers().size());

    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    assertEquals(tt.getHexInfoHash(), seeder.getTorrents().iterator().next().getHexInfoHash());

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      seeder.start(InetAddress.getLocalHost());

      leech.start(InetAddress.getLocalHost());

      waitForFileInDir(downloadDir, "file1.jar");
      assertFilesEqual(new File(TEST_RESOURCES + "/parentFiles/file1.jar"), new File(downloadDir, "file1.jar"));
    } finally {
      leech.stop();
      seeder.stop();
    }
  }

  public void tracker_accepts_torrent_from_seeder() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    try {
      seeder.start(InetAddress.getLocalHost());

      waitForSeeder(seeder.getTorrents().iterator().next().getInfoHash());

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertTrue(peers.values().iterator().next().isCompleted()); // seed
      assertEquals(1, trackedTorrent.seeders());
      assertEquals(0, trackedTorrent.leechers());
    } finally {
      seeder.stop();
    }
  }

  public void tracker_accepts_torrent_from_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      leech.start(InetAddress.getLocalHost());

      waitForPeers(1);

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<String, TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertFalse(peers.values().iterator().next().isCompleted()); // leech
      assertEquals(0, trackedTorrent.seeders());
      assertEquals(1, trackedTorrent.leechers());
    } finally {
      leech.stop();
    }
  }

  public void tracker_removes_peer_after_peer_shutdown() throws IOException, NoSuchAlgorithmException, InterruptedException {
    tracker.setAcceptForeignTorrents(true);
    final SharedTorrent torrent = completeTorrent("file1.jar.torrent");

    final Client c1 = createClient();
    c1.start(InetAddress.getLocalHost());
    c1.addTorrent(torrent);

    final Client c2 = createClient();
    c2.start(InetAddress.getLocalHost());
    c2.addTorrent(completeTorrent("file1.jar.torrent"));

    final TrackedTorrent tt = tracker.getTrackedTorrent(torrent.getHexInfoHash());
    assertTrackedTorrentContainPeers(tt, c1.getPeers());
    assertTrackedTorrentContainPeers(tt, c2.getPeers());

    c2.stop();
    new WaitFor(30 * 1000) {

      @Override
      protected boolean condition() {
        return tt.getPeers().size() == c1.getPeers().size();
      }
    };
    assertTrackedTorrentContainPeers(tt, c1.getPeers());
    assertTrackedTorrentNotContainPeers(tt, c2.getPeers());
  }

  public void tracker_removes_peer_after_timeout() throws IOException, NoSuchAlgorithmException, InterruptedException {
    tracker.setAcceptForeignTorrents(true);
    tracker.setPeerCollectorExpireTimeout(10);
    tracker.stop();
    tracker.start(true);
    final SharedTorrent torrent = completeTorrent("file1.jar.torrent");

    final Client c1 = createClient();
    c1.setAnnounceInterval(2);
    c1.start(InetAddress.getLocalHost());
    c1.addTorrent(torrent);

    final Client c2 = createClient();
    c2.setAnnounceInterval(120);
    c2.start(InetAddress.getLocalHost());
    c2.addTorrent(completeTorrent("file1.jar.torrent"));

    final TrackedTorrent tt = tracker.getTrackedTorrent(torrent.getHexInfoHash());
    assertTrackedTorrentContainPeers(tt, c1.getPeers());
    assertTrackedTorrentContainPeers(tt, c2.getPeers());

    new WaitFor(30 * 1000) {

      @Override
      protected boolean condition() {
        return tt.getPeers().size() == c1.getPeers().size();
      }
    };
    assertTrackedTorrentContainPeers(tt, c1.getPeers());
    assertTrackedTorrentNotContainPeers(tt, c2.getPeers());
  }

  //  @Test(invocationCount = 50)
  public void tracker_accepts_torrent_from_seeder_plus_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      seeder.start(InetAddress.getLocalHost());
      leech.start(InetAddress.getLocalHost());

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      seeder.stop();
      leech.stop();
    }
  }

  private TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
    return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name)));
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(6969);
    tracker.setAnnounceInterval(5);
    tracker.setPeerCollectorExpireTimeout(10);
    this.tracker.start(true);
  }

  private void stopTracker() {
    this.tracker.stop();
  }

  @AfterMethod
  protected void tearDown() throws Exception {
    for (Client client : clientList) {
      client.stop();
    }
    stopTracker();
    tempFiles.cleanup();
  }

  private Client createClient() throws IOException, NoSuchAlgorithmException, InterruptedException {
    final Client client = clientFactory.getClient("");
    clientList.add(client);
    return client;
  }

  private void waitForFileInDir(final File downloadDir, final String fileName) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        return new File(downloadDir, fileName).isFile();
      }
    };

    assertTrue(new File(downloadDir, fileName).isFile());
  }

  private void waitForPeers(final int numPeers) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : tracker.getTrackedTorrents()) {
          if (tt.getPeers().size() == numPeers) return true;
        }

        return false;
      }
    };
  }

  private SharedTorrent completeTorrent(String name) throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    return SharedTorrent.fromFile(torrentFile, parentFiles, false);
  }

  private SharedTorrent incompleteTorrent(String name, File destDir) throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    return SharedTorrent.fromFile(torrentFile, destDir, false);
  }

  private void waitForSeeder(final byte[] torrentHash) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : tracker.getTrackedTorrents()) {
          if (tt.seeders() == 1 && tt.getHexInfoHash().equals(Torrent.byteArrayToHexString(torrentHash))) return true;
        }

        return false;
      }
    };
  }

  private void assertFilesEqual(File f1, File f2) throws IOException {
    assertEquals(f1.length(), f2.length(), "Files sizes differ");
    Checksum c1 = FileUtils.checksum(f1, new CRC32());
    Checksum c2 = FileUtils.checksum(f2, new CRC32());
    assertEquals(c1.getValue(), c2.getValue());
  }

  private void assertTrackedTorrentContainPeers(TrackedTorrent trackedTorrent, Set<SharingPeer> peers) {
    for (SharingPeer peer : peers) {
      assertNotNull(trackedTorrent.getPeer(peer.getShortHexPeerId()),
              String.format("Peer %s is not available for torrent %s", peer.toString(), trackedTorrent.toString()));
    }
  }

  private void assertTrackedTorrentNotContainPeers(TrackedTorrent trackedTorrent, Set<SharingPeer> peers) {
    for (SharingPeer peer : peers) {
      assertNull(trackedTorrent.getPeer(peer.getShortHexPeerId()),
              String.format("Peer %s is not available for torrent %s", peer.toString(), trackedTorrent.toString()));
    }
  }
}
