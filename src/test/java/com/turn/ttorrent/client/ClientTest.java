package com.turn.ttorrent.client;

import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static org.testng.Assert.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

/**
 * @author Sergey.Pak
 *         Date: 7/26/13
 *         Time: 2:32 PM
 */
@Test
public class ClientTest {

  private List<Client> clientList = new ArrayList<Client>();
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;


  @BeforeMethod
  public void setUp() throws IOException {
    BasicConfigurator.configure();
    Logger.getRootLogger().setLevel(Level.INFO);
    tempFiles = new TempFiles();
    Torrent.setHashingThreadsCount(1);
    startTracker();
  }

  public void download_multiple_files() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    int numFiles = 50;
    this.tracker.setAcceptForeignTorrents(true);

    final File srcDir = tempFiles.createTempDir();
    final File downloadDir = tempFiles.createTempDir();

    Client seeder = createClient();
    seeder.start(InetAddress.getLocalHost());
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
      leech.start(InetAddress.getLocalHost());
      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        SharedTorrent st2 = SharedTorrent.fromFile(torrentFile, downloadDir, true);
        leech.addTorrent(st2);
      }

      new WaitFor(90 * 1000) {
        @Override
        protected boolean condition() {
          return listFileNames(downloadDir).containsAll(names);
        }
      };

      assertTrue(listFileNames(downloadDir).equals(names));
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

  //  @Test(invocationCount = 50)
  public void large_file_download() throws IOException, URISyntaxException, NoSuchAlgorithmException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

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
      seeder.start(InetAddress.getLocalHost());
      leech.start(InetAddress.getLocalHost());

      waitForFileInDir(downloadDir, tempFile.getName());
      assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
    } finally {
      seeder.stop(true);
      leech.stop(true);
    }
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

      Torrent torrent = Torrent.create(tempFile, this.tracker.getAnnounceURI(), "Test");
      File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
      torrent.save(torrentFile);

      for (int i = 0; i < numSeeders; i++) {
        Client client = seeders.get(i);
        client.addTorrent(SharedTorrent.fromFile(torrentFile, tempFile.getParentFile(), false));
        client.start(InetAddress.getLocalHost());
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

  public void no_full_seeder_test() throws IOException, URISyntaxException, InterruptedException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48*1024; // lower piece size to reduce disk usage
    final int numSeeders = 6;
    final int piecesCount = numSeeders * 3 + 15;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File tempFile = tempFiles.createTempFile(piecesCount * pieceSize);

      createMultipleSeedersWithDifferentPieces(tempFile, piecesCount, pieceSize, numSeeders, clientsList);
      String baseMD5 = getFileMD5(tempFile, md5);

      validateMultipleClientsResults(clientsList, md5, tempFile, baseMD5);

    } finally {
      for (Client client : clientsList) {
        client.stop();
      }
    }
  }

  public void testDelete() throws IOException, InterruptedException, NoSuchAlgorithmException, URISyntaxException {
    this.tracker.setAcceptForeignTorrents(true);

    final File srcDir = tempFiles.createTempDir();
    final File downloadDir = tempFiles.createTempDir();
    final File downloadDir2 = tempFiles.createTempDir();

    Client seeder = createClient();
    seeder.start(InetAddress.getLocalHost());


    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();
    final Set<String> names = new HashSet<String>();
    File tempFile = tempFiles.createTempFile(513 * 1024);
    final File srcFile = new File(srcDir, tempFile.getName());
    assertTrue(tempFile.renameTo(srcFile));

    Torrent torrent = Torrent.create(srcFile, announceURI, "Test");
    File torrentFile = new File(srcFile.getParentFile(), srcFile.getName() + ".torrent");
    torrent.save(torrentFile);
    names.add(srcFile.getName());
    seeder.addTorrent(new SharedTorrent(torrent, srcFile.getParentFile(), false));


    Client leech1 = createClient();
    leech1.start(InetAddress.getLocalHost());
    SharedTorrent sharedTorrent = SharedTorrent.fromFile(torrentFile, downloadDir, true);
    leech1.addTorrent(sharedTorrent);

    new WaitFor(15 * 1000) {
      @Override
      protected boolean condition() {
        return listFileNames(downloadDir).contains(srcFile.getName());
      }
    };

    assertTrue(listFileNames(downloadDir).contains(srcFile.getName()));

    leech1.stop();
    leech1=null;

    srcFile.delete();

    Client leech2 = createClient();
    leech2.start(InetAddress.getLocalHost());
    SharedTorrent st2 = SharedTorrent.fromFile(torrentFile, downloadDir2, true);
    leech2.addTorrent(st2);

    Thread.sleep(10 * 1000); // the file no longer exists. Stop seeding and announce that to tracker
    final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(torrent.getHexInfoHash());
    assertEquals(0, seeder.getTorrents().size());
    for (SharingPeer peer : seeder.getPeers()) {
      assertFalse(trackedTorrent.getPeers().keySet().contains(peer.getTorrentHexInfoHash()));
    }
  }

  //  @Test(invocationCount = 50)
  public void corrupted_seeder_repair()  throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48*1024; // lower piece size to reduce disk usage
    final int numSeeders = 6;
    final int piecesCount = numSeeders +7;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);

      createMultipleSeedersWithDifferentPieces(baseFile, piecesCount, pieceSize, numSeeders, clientsList);
      String baseMD5 = getFileMD5(baseFile, md5);
      Client firstClient = clientsList.get(0);
      final SharedTorrent torrent = firstClient.getTorrents().iterator().next();
      final File file = new File(torrent.getParentFile(), torrent.getFilenames().get(0));
      final int oldByte;
      {
        RandomAccessFile raf = new RandomAccessFile(file, "rw");
        raf.seek(0);
        oldByte = raf.read();
        raf.seek(0);
        // replacing the byte
        if (oldByte != 35) {
          raf.write(35);
        } else {
          raf.write(45);
        }
        raf.close();
      }
      final WaitFor waitFor = new WaitFor(30 * 1000) {
        @Override
        protected boolean condition() {
          for (Client client : clientsList) {
            final SharedTorrent next = client.getTorrents().iterator().next();
            if (next.getCompletedPieces().cardinality() < next.getPieceCount()-1){
              return false;
            }
          }
          return true;
        }
      };

      if (!waitFor.isMyResult()){
        fail("All seeders didn't get their files");
      }
      Thread.sleep(10*1000);
      {
        byte[] piece = new byte[pieceSize];
        FileInputStream fin = new FileInputStream(baseFile);
        fin.read(piece);
        fin.close();
        RandomAccessFile raf;
        try {
          raf = new RandomAccessFile(file, "rw");
          raf.seek(0);
          raf.write(oldByte);
          raf.close();
        } catch (FileNotFoundException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }

      validateMultipleClientsResults(clientsList, md5, baseFile, baseMD5);

    } finally {
      for (Client client : clientsList) {
        client.stop();
      }
    }
  }

  public void unlock_file_when_no_leechers() throws InterruptedException, NoSuchAlgorithmException, IOException {
    Client seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.start(InetAddress.getLocalHost());

    downloadAndStop(torrent, 15*1000, createClient());
    Thread.sleep(2*1000);
    assertTrue(dwnlFile.exists() && dwnlFile.isFile());
    dwnlFile.delete();
    assertFalse(dwnlFile.exists());
  }

  public void download_many_times() throws InterruptedException, NoSuchAlgorithmException, IOException {
    Client seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.start(InetAddress.getLocalHost());

    for(int i=0; i<5; i++) {
      downloadAndStop(torrent, 20*1000, createClient());
      Thread.sleep(5*1000);
    }
  }

  public void download_io_error() throws InterruptedException, NoSuchAlgorithmException, IOException{
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 14);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.start(InetAddress.getLocalHost());

    for(int i=0; i<5; i++) {
      final AtomicInteger interrupts = new AtomicInteger(0);
      final Client leech = new Client(){
        @Override
        public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
          super.handlePieceCompleted(peer, piece);
          if (piece.getIndex()%4==0 && interrupts.incrementAndGet() <= 2){
            peer.getSocketChannel().close();
          }
        }
      };
      //manually add leech here for graceful shutdown.
      clientList.add(leech);
      downloadAndStop(torrent, 45 * 1000, leech);
      Thread.sleep(2*1000);
    }

  }

  public void download_uninterruptibly_positive() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final SharedTorrent st = new SharedTorrent(torrent, tempFiles.createTempDir(), true);
    leecher.downloadUninterruptibly(st, 10);

    assertTrue(st.getClientState()==ClientState.SEEDING);
  }

  public void download_uninterruptibly_negative() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAcceptForeignTorrents(true);
    final AtomicInteger downloadedPiecesCount = new AtomicInteger(0);
    final Client seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    final Client leecher = new Client(){
      @Override
      public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
        super.handlePieceCompleted(peer, piece);
        if (downloadedPiecesCount.incrementAndGet() > 10){
          seeder.stop();
        }
      }
    };
    clientList.add(leecher);
    leecher.start(InetAddress.getLocalHost());
    final SharedTorrent st = new SharedTorrent(torrent, tempFiles.createTempDir(), true);
    try {
      leecher.downloadUninterruptibly(st, 20);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (IOException ex){
      assertEquals(st.getClientState(),ClientState.DONE);
    }

  }



  private void downloadAndStop(Torrent torrent, long timeout, final Client leech) throws IOException, NoSuchAlgorithmException, InterruptedException {
    final File tempDir = tempFiles.createTempDir();
    leech.addTorrent(new SharedTorrent(torrent, tempDir, false));
    leech.start(InetAddress.getLocalHost());

    final WaitFor waitFor = new WaitFor(timeout) {
      @Override
      protected boolean condition() {
        final SharedTorrent leechTorrent = leech.getTorrents().iterator().next();
        if (leech.isRunning()) {
          return leechTorrent.getClientState() == ClientState.SEEDING;
        } else {
          return true;
        }
      }
    };

    assertTrue(waitFor.isMyResult(), "File wasn't downloaded in time");
  }

  private void validateMultipleClientsResults(final List<Client> clientsList, MessageDigest md5, File baseFile, String baseMD5) throws IOException {
    final WaitFor waitFor = new WaitFor(75 * 1000) {
      @Override
      protected boolean condition() {
        boolean retval = true;
        for (Client client : clientsList) {
          if (!retval) return false;
          final boolean torrentState = client.getTorrents().iterator().next().getClientState() == ClientState.SEEDING;
          retval = retval && torrentState;
        }
        return retval;
      }
    };

    assertTrue(waitFor.isMyResult(), "All seeders didn't get their files");
    // check file contents here:
    for (Client client : clientsList) {
      final SharedTorrent st = client.getTorrents().iterator().next();
      final File file = new File(st.getParentFile(), st.getFilenames().get(0));
      assertEquals(baseMD5, getFileMD5(file, md5), String.format("MD5 hash is invalid. C:%s, O:%s ",
              file.getAbsolutePath(), baseFile.getAbsolutePath()));
    }
  }

  private void createMultipleSeedersWithDifferentPieces(File baseFile, int piecesCount, int pieceSize, int numSeeders,
                                                        List<Client> clientList) throws IOException, InterruptedException, NoSuchAlgorithmException, URISyntaxException {

    List<byte[]> piecesList = new ArrayList<byte[]>(piecesCount);
    FileInputStream fin = new FileInputStream(baseFile);
    for (int i=0; i<piecesCount; i++){
      byte[] piece = new byte[pieceSize];
      fin.read(piece);
      piecesList.add(piece);
    }
    fin.close();

    final long torrentFileLength = baseFile.length();
    Torrent torrent = Torrent.create(baseFile, null, this.tracker.getAnnounceURI(), null,  "Test", pieceSize);
    File torrentFile = new File(baseFile.getParentFile(), baseFile.getName() + ".torrent");
    torrent.save(torrentFile);


    for (int i=0; i<numSeeders; i++){
      final File baseDir = tempFiles.createTempDir();
      final File seederPiecesFile = new File(baseDir, baseFile.getName());
      RandomAccessFile raf = new RandomAccessFile(seederPiecesFile, "rw");
      raf.setLength(torrentFileLength);
      for (int pieceIdx=i; pieceIdx<piecesCount; pieceIdx += numSeeders){
        raf.seek(pieceIdx*pieceSize);
        raf.write(piecesList.get(pieceIdx));
      }
      Client client = createClient();
      clientList.add(client);
      client.addTorrent(new SharedTorrent(torrent, baseDir, false));
      client.start(InetAddress.getLocalHost());
    }
  }

  private String getFileMD5(File file, MessageDigest digest) throws IOException {
    DigestInputStream dIn = new DigestInputStream(new FileInputStream(file), digest);
    while (dIn.read() >= 0);
    return dIn.getMessageDigest().toString();
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

  private void waitForFileInDir(final File downloadDir, final String fileName) {
    new WaitFor() {
      @Override
      protected boolean condition() {
        return new File(downloadDir, fileName).isFile();
      }
    };

    assertTrue(new File(downloadDir, fileName).isFile());
  }



  @AfterMethod
  protected void tearDown() throws Exception {
    stopTracker();
    for (Client client : clientList) {
      client.stop();
    }
    tempFiles.cleanup();
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(6969);
    tracker.setAnnounceInterval(5);
    this.tracker.start(true);
  }

  private Client createClient() throws IOException, NoSuchAlgorithmException, InterruptedException {
    final Client client = new Client();
    clientList.add(client);
    return client;
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
    assertEquals(f1.length(), f2.length(), "Files sizes differ");
    Checksum c1 = FileUtils.checksum(f1, new CRC32());
    Checksum c2 = FileUtils.checksum(f2, new CRC32());
    assertEquals(c1.getValue(), c2.getValue());
  }
}
