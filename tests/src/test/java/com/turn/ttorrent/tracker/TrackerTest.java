package com.turn.ttorrent.tracker;

import com.turn.ttorrent.CommunicationManagerFactory;
import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.Utils;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.CommunicationManager;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.client.storage.FairPieceStorageFactory;
import com.turn.ttorrent.client.storage.FileCollectionStorage;
import com.turn.ttorrent.common.*;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static com.turn.ttorrent.tracker.Tracker.ANNOUNCE_URL;
import static com.turn.ttorrent.tracker.TrackerUtils.TEST_RESOURCES;
import static org.testng.Assert.*;

@Test
public class TrackerTest {

  private Tracker tracker;
  private TempFiles tempFiles;
  //  private String myLogfile;
  private List<CommunicationManager> communicationManagerList = new ArrayList<CommunicationManager>();

  private final CommunicationManagerFactory communicationManagerFactory;


  public TrackerTest() {
    communicationManagerFactory = new CommunicationManagerFactory();
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS}] %6p - %20.20c - %m %n")));
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
  }

  @BeforeMethod
  protected void setUp() throws Exception {
    tempFiles = new TempFiles();
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

  public void testPeerWithManyInterfaces() throws Exception {
    List<InetAddress> selfAddresses = new ArrayList<InetAddress>();
    final Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
    while (networkInterfaces.hasMoreElements()) {
      NetworkInterface ni = networkInterfaces.nextElement();
      final Enumeration<InetAddress> inetAddresses = ni.getInetAddresses();
      while (inetAddresses.hasMoreElements()) {
        InetAddress inetAddress = inetAddresses.nextElement();
        if (inetAddress instanceof Inet6Address) continue;// ignore IPv6 addresses

        selfAddresses.add(inetAddress);
      }
    }

    final InetAddress[] inetAddresses = selfAddresses.toArray(new InetAddress[selfAddresses.size()]);
    CommunicationManager seeder = createCommunicationManager();
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    final String hexInfoHash = seeder.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath()).getHexInfoHash();
    seeder.start(inetAddresses);
    final WaitFor waitFor = new WaitFor(10000) {
      @Override
      protected boolean condition() {
        final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(hexInfoHash);
        return trackedTorrent != null && trackedTorrent.getPeers().size() >= inetAddresses.length;
      }
    };

    assertTrue(waitFor.isMyResult());

    final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(hexInfoHash);

    Set<String> expectedIps = new HashSet<String>();
    for (InetAddress inetAddress : inetAddresses) {
      expectedIps.add(inetAddress.getHostAddress());
    }
    Set<String> actualIps = new HashSet<String>();
    for (TrackedPeer peer : trackedTorrent.getPeers().values()) {
      actualIps.add(peer.getIp());
    }

    assertEquals(actualIps, expectedIps);
    assertEquals(inetAddresses.length, actualIps.size());

  }

  public void test_share_and_download() throws IOException, InterruptedException {
    final TrackedTorrent tt = this.tracker.announce(loadTorrent("file1.jar.torrent"));
    assertEquals(0, tt.getPeers().size());

    CommunicationManager seeder = createCommunicationManager();
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    seeder.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());

    assertEquals(tt.getHexInfoHash(), seeder.getTorrentsStorage().announceableTorrents().iterator().next().getHexInfoHash());

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createCommunicationManager();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());

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

  public void tracker_accepts_torrent_from_seeder() throws IOException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    CommunicationManager seeder = createCommunicationManager();
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    seeder.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());

    try {
      seeder.start(InetAddress.getLocalHost());

      waitForSeeder(seeder.getTorrentsStorage().announceableTorrents().iterator().next().getInfoHash());

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<PeerUID, TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertTrue(peers.values().iterator().next().isCompleted()); // seed
      assertEquals(1, trackedTorrent.seeders());
      assertEquals(0, trackedTorrent.leechers());
    } finally {
      seeder.stop();
    }
  }

  public void tracker_accepts_torrent_from_leech() throws IOException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createCommunicationManager();
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());

    try {
      leech.start(InetAddress.getLocalHost());

      new WaitFor() {
        @Override
        protected boolean condition() {
          for (TrackedTorrent tt : tracker.getTrackedTorrents()) {
            if (tt.getPeers().size() == 1) return true;
          }

          return false;
        }
      };

      Collection<TrackedTorrent> trackedTorrents = this.tracker.getTrackedTorrents();
      assertEquals(1, trackedTorrents.size());

      TrackedTorrent trackedTorrent = trackedTorrents.iterator().next();
      Map<PeerUID, TrackedPeer> peers = trackedTorrent.getPeers();
      assertEquals(1, peers.size());
      assertFalse(peers.values().iterator().next().isCompleted()); // leech
      assertEquals(0, trackedTorrent.seeders());
      assertEquals(1, trackedTorrent.leechers());
    } finally {
      leech.stop();
    }
  }

  public void tracker_removes_peer_after_peer_shutdown() throws IOException, InterruptedException {
    tracker.setAcceptForeignTorrents(true);
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");

    final CommunicationManager c1 = createCommunicationManager();
    c1.start(InetAddress.getLocalHost());
    c1.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());

    final CommunicationManager c2 = createCommunicationManager();
    c2.start(InetAddress.getLocalHost());
    c2.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());

    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return tracker.getTrackedTorrents().size() == 1;
      }
    };

    final TrackedTorrent tt = tracker.getTrackedTorrents().iterator().next();

    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return tt.getPeers().size() == 2;
      }
    };

    final InetSocketAddress c1Address = new InetSocketAddress(InetAddress.getLocalHost(), c1.getConnectionManager().getBindPort());
    final InetSocketAddress c2Address = new InetSocketAddress(InetAddress.getLocalHost(), c2.getConnectionManager().getBindPort());
    assertTrue(tt.getPeers().containsKey(new PeerUID(c1Address, tt.getHexInfoHash())));
    assertTrue(tt.getPeers().containsKey(new PeerUID(c2Address, tt.getHexInfoHash())));

    c2.stop();
    new WaitFor(30 * 1000) {

      @Override
      protected boolean condition() {
        return tt.getPeers().size() == 1;
      }
    };
    assertTrue(tt.getPeers().containsKey(new PeerUID(c1Address, tt.getHexInfoHash())));
    assertFalse(tt.getPeers().containsKey(new PeerUID(c2Address, tt.getHexInfoHash())));
  }

  public void tracker_removes_peer_after_timeout() throws IOException, InterruptedException {
    tracker.setAcceptForeignTorrents(true);
    tracker.stop();
    tracker.start(true);
    final SharedTorrent torrent = completeTorrent("file1.jar.torrent");
    tracker.setPeerCollectorExpireTimeout(5);

    int peerPort = 6885;
    String peerHost = InetAddress.getLocalHost().getHostAddress();
    final String announceUrlC1 = "http://localhost:6969/announce?info_hash=%B9-8%04lv%D79H%E1LB%DF%99%2C%AF%25H%9D%08&peer_id=-TO0042-97ec308c9637&" +
            "port=" + peerPort + "&uploaded=0&downloaded=0&left=0&compact=1&no_peer_id=0&ip=" + peerHost;

    try {
      final URLConnection connection = new URL(announceUrlC1).openConnection();
      connection.getInputStream().close();
    } catch (Exception e) {
      fail("", e);
    }

    final CommunicationManager c2 = createCommunicationManager();
    c2.setAnnounceInterval(120);
    c2.start(InetAddress.getLocalHost());
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    c2.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());

    final TrackedTorrent tt = tracker.getTrackedTorrent(torrent.getHexInfoHash());
    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {

        return tt.getPeers().size() == 2;
      }
    };

    final InetSocketAddress c1Address = new InetSocketAddress(peerHost, peerPort);
    final InetSocketAddress c2Address = new InetSocketAddress(InetAddress.getLocalHost(), c2.getConnectionManager().getBindPort());
    assertTrue(tt.getPeers().containsKey(new PeerUID(c1Address, tt.getHexInfoHash())));
    assertTrue(tt.getPeers().containsKey(new PeerUID(c2Address, tt.getHexInfoHash())));

    new WaitFor(30 * 1000) {

      @Override
      protected boolean condition() {
        try {
          final URLConnection connection = new URL(announceUrlC1).openConnection();
          connection.getInputStream().close();
        } catch (Exception e) {
          e.printStackTrace();
        }
        return tt.getPeers().size() == 1;
      }
    };
    assertEquals(tt.getPeers().size(), 1);
    assertTrue(tt.getPeers().containsKey(new PeerUID(c1Address, tt.getHexInfoHash())));
    assertFalse(tt.getPeers().containsKey(new PeerUID(c2Address, tt.getHexInfoHash())));
  }

  //  @Test(invocationCount = 50)
  public void tracker_accepts_torrent_from_seeder_plus_leech() throws IOException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    CommunicationManager seeder = createCommunicationManager();
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    seeder.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createCommunicationManager();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());

    try {
      seeder.start(InetAddress.getLocalHost());
      leech.start(InetAddress.getLocalHost());

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      seeder.stop();
      leech.stop();
    }
  }

  private TrackedTorrent loadTorrent(String name) throws IOException {
    TorrentMetadata torrentMetadata = new TorrentParser().parseFromFile(new File(TEST_RESOURCES + "/torrents", name));
    return new TrackedTorrent(torrentMetadata.getInfoHash());
  }

  private void startTracker() throws IOException {
    int port = 6969;
    this.tracker = new Tracker(port, "http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port + "" + ANNOUNCE_URL);
    tracker.setAnnounceInterval(5);
    tracker.setPeerCollectorExpireTimeout(10);
    this.tracker.start(true);
  }

  private void stopTracker() {
    this.tracker.stop();
  }

  @AfterMethod
  protected void tearDown() throws Exception {
    for (CommunicationManager communicationManager : communicationManagerList) {
      communicationManager.stop();
    }
    stopTracker();
    tempFiles.cleanup();
  }

  private CommunicationManager createCommunicationManager() {
    final CommunicationManager communicationManager = communicationManagerFactory.getClient("");
    communicationManagerList.add(communicationManager);
    return communicationManager;
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

  private SharedTorrent completeTorrent(String name) throws IOException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    TorrentMetadata torrentMetadata = new TorrentParser().parseFromFile(torrentFile);
    return SharedTorrent.fromFile(torrentFile,
            FairPieceStorageFactory.INSTANCE.createStorage(torrentMetadata, FileCollectionStorage.create(torrentMetadata, parentFiles)),
            new TorrentStatistic());
  }

  private SharedTorrent incompleteTorrent(String name, File destDir) throws IOException {
    File torrentFile = new File(TEST_RESOURCES + "/torrents", name);
    TorrentMetadata torrentMetadata = new TorrentParser().parseFromFile(torrentFile);
    return SharedTorrent.fromFile(torrentFile,
              FairPieceStorageFactory.INSTANCE.createStorage(torrentMetadata, FileCollectionStorage.create(torrentMetadata, destDir)),
              new TorrentStatistic());
  }

  private void waitForSeeder(final byte[] torrentHash) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : tracker.getTrackedTorrents()) {
          if (tt.seeders() == 1 && tt.getHexInfoHash().equals(TorrentUtils.byteArrayToHexString(torrentHash))) return true;
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
}
