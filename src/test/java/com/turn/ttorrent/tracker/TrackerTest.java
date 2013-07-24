package com.turn.ttorrent.tracker;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.SharedTorrent;
import com.turn.ttorrent.common.Torrent;
import junit.framework.TestCase;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.*;
import org.apache.log4j.spi.RootLogger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.*;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

@Test
public class TrackerTest extends TestCase {
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;
  private String myLogfile;

  @Override
  @BeforeMethod
  protected void setUp() throws Exception {
//    org.apache.log4j.BasicConfigurator.configure();
    final Logger rootLogger = RootLogger.getRootLogger();
    rootLogger.removeAllAppenders();
    rootLogger.setLevel(Level.ALL);
    myLogfile = String.format("test-%d.txt", System.currentTimeMillis());
    final Layout layout = new PatternLayout(PatternLayout.TTCC_CONVERSION_PATTERN);
    final FileAppender newAppender = new FileAppender(layout, myLogfile);
    rootLogger.addAppender(newAppender);
    super.setUp();
    tempFiles = new TempFiles();
    Torrent.setHashingThreadsCount(1);
    startTracker();
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
      seeder.share();

      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
      assertFilesEqual(new File(TEST_RESOURCES + "/parentFiles/file1.jar"), new File(downloadDir, "file1.jar"));
    } finally {
      leech.stop(true);
      seeder.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_seeder() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    try {
      seeder.share();

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
      seeder.stop(true);
    }
  }

  public void tracker_accepts_torrent_from_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      leech.download();

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
      leech.stop(true);
    }
  }

  @Test(invocationCount = 50)
  public void tracker_accepts_torrent_from_seeder_plus_leech() throws IOException, NoSuchAlgorithmException, InterruptedException {
/*
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());
*/

    Client seeder = createClient();
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(incompleteTorrent("file1.jar.torrent", downloadDir));

    try {
      seeder.share();
      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      seeder.stop(true);
      leech.stop(true);
    }
  }

  @Test(invocationCount = 50)
  public void download_multiple_files() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    int numFiles = 200;
//    this.tracker.setAcceptForeignTorrents(true);

    final File srcDir = tempFiles.createTempDir();
    final File downloadDir = tempFiles.createTempDir();

    Client seeder = createClient();
    seeder.share();
    Client leech = null;


    try {
      URL announce = new URL("http://127.0.0.1:6969/announce");
      URI announceURI = announce.toURI();
      final Set<String> names = new HashSet<String>();
      List<File> filesToShare = new ArrayList<File>();
      for (int i = 0; i < numFiles; i++) {
        File tempFile = tempFiles.createTempFile(513 * 1024);
        File srcFile = new File(srcDir, tempFile.getName());
        assertTrue(tempFile.renameTo(srcFile));

        Torrent torrent = Torrent.create(srcFile, announceURI, "Test");
        File torrentFile = new File(srcFile.getParentFile(), srcFile.getName() + ".torrent");
        torrent.save(torrentFile);
        filesToShare.add(srcFile);
        names.add(srcFile.getName());
      }

      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        SharedTorrent st1 = SharedTorrent.fromFile(torrentFile, f.getParentFile(), true);
        seeder.addTorrent(st1);
      }
      leech = createClient();
      leech.share();
      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        SharedTorrent st2 = SharedTorrent.fromFile(torrentFile, downloadDir, true);
        leech.addTorrent(st2);
      }

      new WaitFor(90 * 1000, 100) {
        @Override
        protected boolean condition() {
          return listFileNames(downloadDir).containsAll(names);
        }
      };

      assertTrue(myLogfile, listFileNames(downloadDir).equals(names));
    } finally {
      leech.stop(true);
      seeder.stop(true);
    }
  }

  private Set<String> listFileNames(File downloadDir) {
    if (downloadDir == null) return Collections.emptySet();
    Set<String> names = new HashSet<String>();
    File[] files = downloadDir.listFiles();
    if (files == null) return Collections.emptySet();
    for (File f : files) {
      names.add(f.getName());
    }
    return names;
  }

  @Test(invocationCount = 50)
  public void large_file_download() throws IOException, URISyntaxException, NoSuchAlgorithmException, InterruptedException {
//    this.tracker.setAcceptForeignTorrents(true);

    File tempFile = tempFiles.createTempFile(201 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    Torrent torrent = Torrent.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    torrent.save(torrentFile);

    Client seeder = createClient();
    seeder.addTorrent(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile(), true));

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(SharedTorrent.fromFile(torrentFile, downloadDir, true));

    try {
      seeder.share();
      leech.download();

      waitForFileInDir(downloadDir, tempFile.getName());
      assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
    } finally {
      seeder.stop(true);
      leech.stop(true);
    }
  }

  public void test_announce() throws IOException, NoSuchAlgorithmException {
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    this.tracker.announce(loadTorrent("file1.jar.torrent"));

    assertEquals(1, this.tracker.getTrackedTorrents().size());
  }

  public void more_than_one_seeder_for_same_torrent() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    int numSeeders = 5;
    List<Client> seeders = new ArrayList<Client>();
    for (int i = 0; i < numSeeders; i++) {
      seeders.add(createClient());
    }

    try {
      File tempFile = tempFiles.createTempFile(100 * 1024);

      Torrent torrent = Torrent.create(tempFile, this.tracker.getAnnounceUrl().toURI(), "Test");
      File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
      torrent.save(torrentFile);

      for (int i = 0; i < numSeeders; i++) {
        Client client = seeders.get(i);
        client.addTorrent(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile(), false));
        client.share();
      }

      waitForPeers(numSeeders);

      Collection<TrackedTorrent> torrents = this.tracker.getTrackedTorrents();
      assertEquals(1, torrents.size());
      assertEquals(numSeeders, torrents.iterator().next().seeders());
    } finally {
      for (Client client : seeders) {
        client.stop();
      }
    }

  }

  private void waitForSeeder(final byte[] torrentHash) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
          if (tt.seeders() == 1 && tt.getHexInfoHash().equals(Torrent.byteArrayToHexString(torrentHash))) return true;
        }

        return false;
      }
    };
  }

  private void waitForPeers(final int numPeers) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        for (TrackedTorrent tt : TrackerTest.this.tracker.getTrackedTorrents()) {
          if (tt.getPeers().size() == numPeers) return true;
        }

        return false;
      }
    };
  }

/*
  public void utorrent_test_leech() throws IOException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    new WaitFor(3600 * 1000) {
      @Override
      protected boolean condition() {
        return TrackerTest.this.tracker.getTrackedTorrents().size() == 1;
      }
    };
  }
*/

/*
  public void utorrent_test_seed() throws IOException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient("file1.jar.torrent", downloadDir);

    waitForSeeder(leech.getTorrents().iterator().next().getInfoHash());

    TrackedTorrent tt = this.tracker.getTrackedTorrents().iterator().next();
    assertEquals(1, tt.seeders());

    try {
      leech.download();

      waitForFileInDir(downloadDir, "file1.jar");
    } finally {
      leech.stop(true);
    }
  }
*/


  private void waitForFileInDir(final File downloadDir, final String fileName) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        return new File(downloadDir, fileName).isFile();
      }
    };

    assertTrue(this.myLogfile, new File(downloadDir, fileName).isFile());
  }

  private TrackedTorrent loadTorrent(String name) throws IOException, NoSuchAlgorithmException {
    return new TrackedTorrent(Torrent.load(new File(TEST_RESOURCES + "/torrents", name), null));
  }


  @Override
  @AfterMethod
  protected void tearDown() throws Exception {
    super.tearDown();
    stopTracker();
    tempFiles.cleanup();
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(new InetSocketAddress(6969));
//    this.tracker.start();
  }

  private Client createClient() throws IOException, NoSuchAlgorithmException, InterruptedException {
    return new Client(InetAddress.getLocalHost());
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

  private void stopTracker() {
    this.tracker.stop();
  }

  private void assertFilesEqual(File f1, File f2) throws IOException {
    assertEquals("Files size differs", f1.length(), f2.length());
    Checksum c1 = FileUtils.checksum(f1, new CRC32());
    Checksum c2 = FileUtils.checksum(f2, new CRC32());
    assertEquals(c1.getValue(), c2.getValue());
  }
}
