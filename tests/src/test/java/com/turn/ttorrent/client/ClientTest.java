package com.turn.ttorrent.client;

import com.turn.ttorrent.ClientFactory;
import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.Utils;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.*;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.tracker.TrackedPeer;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static com.turn.ttorrent.ClientFactory.DEFAULT_POOL_SIZE;
import static org.testng.Assert.*;

/**
 * @author Sergey.Pak
 * Date: 7/26/13
 * Time: 2:32 PM
 */
@Test(timeOut = 600000)
public class ClientTest {

  private ClientFactory clientFactory;

  private List<Client> clientList;
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;

  public ClientTest() {
    clientFactory = new ClientFactory();
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
    TorrentCreator.setHashingThreadsCount(1);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    tempFiles = new TempFiles();
    clientList = new ArrayList<Client>();
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    startTracker();
  }

  private void saveTorrent(Torrent torrent, File file) throws IOException {
    FileOutputStream fos = new FileOutputStream(file);
    fos.write(new TorrentSerializer().serialize(torrent));
    fos.close();
  }

  public void testThatSeederIsNotReceivedHaveMessages() throws Exception {
    final ExecutorService workerES = Executors.newFixedThreadPool(10);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final AtomicBoolean isSeederReceivedHaveMessage = new AtomicBoolean(false);
    Client seeder = new Client(workerES, validatorES) {

      @Override
      public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel) {
        return new SharingPeer(host, port, peerId, torrent, getConnectionManager(), this, channel) {
          @Override
          public synchronized void handleMessage(PeerMessage msg) {
            if (msg instanceof PeerMessage.HaveMessage) {
              isSeederReceivedHaveMessage.set(true);
            }
            super.handleMessage(msg);
          }
        };
      }

      @Override
      public void stop() {
        super.stop();
        workerES.shutdown();
        validatorES.shutdown();
      }
    };
    clientList.add(seeder);

    File tempFile = tempFiles.createTempFile(100 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    Torrent torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    seeder.seedTorrent(torrentFile.getAbsolutePath(), tempFile.getParent());
    seeder.start(InetAddress.getLocalHost());

    waitForSeederIsAnnounsedOnTracker(torrent.getHexInfoHash());

    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), 10);
    assertFalse(isSeederReceivedHaveMessage.get());
  }

  private void waitForSeederIsAnnounsedOnTracker(final String hexInfoHash) {
    final WaitFor waitFor = new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return tracker.getTrackedTorrent(hexInfoHash) != null;
      }
    };
    assertTrue(waitFor.isMyResult());
  }


  //  @Test(invocationCount = 50)
  public void download_multiple_files() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    int numFiles = 50;
    this.tracker.setAcceptForeignTorrents(true);

    final File srcDir = tempFiles.createTempDir();
    final File downloadDir = tempFiles.createTempDir();

    Client seeder = createClient("seeder");
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

        Torrent torrent = TorrentCreator.create(srcFile, announceURI, "Test");
        File torrentFile = new File(srcFile.getParentFile(), srcFile.getName() + ".torrent");
        saveTorrent(torrent, torrentFile);
        filesToShare.add(srcFile);
        names.add(srcFile.getName());
      }

      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        seeder.addTorrent(torrentFile.getAbsolutePath(), f.getParent());
      }
      leech = createClient("leecher");
      leech.start(new InetAddress[]{InetAddress.getLocalHost()}, 5, null);
      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());
      }

      new WaitFor(60 * 1000) {
        @Override
        protected boolean condition() {

          final Set<String> strings = listFileNames(downloadDir);
          int count = 0;
          final List<String> partItems = new ArrayList<String>();
          for (String s : strings) {
            if (s.endsWith(".part")) {
              count++;
              partItems.add(s);
            }
          }
          if (count < 5) {

            System.err.printf("Count: %d. Items: %s%n", count, Arrays.toString(partItems.toArray()));
          }
          return strings.containsAll(names);
        }
      };

      assertEquals(listFileNames(downloadDir), names);
    } finally {
      leech.stop();
      seeder.stop();
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

    Torrent torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    Client seeder = createClient();
    seeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent());

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());

    try {
      seeder.start(InetAddress.getLocalHost());
      leech.start(InetAddress.getLocalHost());

      waitForFileInDir(downloadDir, tempFile.getName());
      assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
    } finally {
      seeder.stop();
      leech.stop();
    }
  }

  @Test(enabled = false)
  public void endgameModeTest() throws Exception {
    this.tracker.setAcceptForeignTorrents(true);
    final int numSeeders = 2;
    List<Client> seeders = new ArrayList<Client>();
    final AtomicInteger skipPiecesCount = new AtomicInteger(1);
    for (int i = 0; i < numSeeders; i++) {
      final ExecutorService es = Executors.newFixedThreadPool(10);
      final ExecutorService validatorES = Executors.newFixedThreadPool(4);
      final Client seeder = new Client(es, validatorES) {
        @Override
        public void stop() {
          super.stop();
          es.shutdownNow();
          validatorES.shutdownNow();
        }

        @Override
        public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel) {
          return new SharingPeer(host, port, peerId, torrent, getConnectionManager(), this, channel) {
            @Override
            public void send(PeerMessage message) throws IllegalStateException {
              if (message instanceof PeerMessage.PieceMessage) {
                if (skipPiecesCount.getAndDecrement() > 0) {
                  return;
                }
              }
              super.send(message);
            }
          };
        }
      };
      seeders.add(seeder);
      clientList.add(seeder);
    }
    File tempFile = tempFiles.createTempFile(1024 * 20 * 1024);

    Torrent torrent = TorrentCreator.create(tempFile, this.tracker.getAnnounceURI(), "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    for (int i = 0; i < numSeeders; i++) {
      Client client = seeders.get(i);
      client.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent(), true, false);
      client.start(InetAddress.getLocalHost());
    }

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.start(InetAddress.getLocalHost());
    leech.downloadUninterruptibly(
            torrentFile.getAbsolutePath(),
            downloadDir.getParent(),
            20);

    waitForFileInDir(downloadDir, tempFile.getName());

  }


  public void more_than_one_seeder_for_same_torrent() throws IOException, NoSuchAlgorithmException, InterruptedException, URISyntaxException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    final int numSeeders = 5;
    List<Client> seeders = new ArrayList<Client>();
    for (int i = 0; i < numSeeders; i++) {
      seeders.add(createClient());
    }

    try {
      File tempFile = tempFiles.createTempFile(100 * 1024);

      Torrent torrent = TorrentCreator.create(tempFile, this.tracker.getAnnounceURI(), "Test");
      File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
      saveTorrent(torrent, torrentFile);

      for (int i = 0; i < numSeeders; i++) {
        Client client = seeders.get(i);
        client.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent(), true, false);
        client.start(InetAddress.getLocalHost());
      }

      new WaitFor() {
        @Override
        protected boolean condition() {
          for (TrackedTorrent tt : tracker.getTrackedTorrents()) {
            if (tt.getPeers().size() == numSeeders) return true;
          }

          return false;
        }
      };

      Collection<TrackedTorrent> torrents = this.tracker.getTrackedTorrents();
      assertEquals(torrents.size(), 1);
      assertEquals(numSeeders, torrents.iterator().next().seeders());
    } finally {
      for (Client client : seeders) {
        client.stop();
      }
    }

  }

  public void testThatDownloadStatisticProvidedToTracker() throws Exception {
    final ExecutorService executorService = Executors.newFixedThreadPool(8);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final AtomicInteger countOfTrackerResponses = new AtomicInteger(0);
    Client leecher = new Client(executorService, validatorES) {
      @Override
      public void handleDiscoveredPeers(List<Peer> peers, String hexInfoHash) {
        super.handleDiscoveredPeers(peers, hexInfoHash);
        countOfTrackerResponses.incrementAndGet();
      }

      @Override
      public void stop() {
        super.stop();
        executorService.shutdownNow();
        validatorES.shutdownNow();
      }
    };

    clientList.add(leecher);

    final int fileSize = 2 * 1025 * 1024;
    File tempFile = tempFiles.createTempFile(fileSize);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    Torrent torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), false, false);
    leecher.start(InetAddress.getLocalHost());
    final AnnounceableFileTorrent announceableTorrent = (AnnounceableFileTorrent)
            leecher.getTorrentsStorage().announceableTorrents().iterator().next();

    final SharedTorrent sharedTorrent = leecher.getTorrentsStorage().putIfAbsentActiveTorrent(announceableTorrent.getHexInfoHash(),
            leecher.getTorrentLoader().loadTorrent(announceableTorrent));

    sharedTorrent.init();

    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return countOfTrackerResponses.get() == 1;
      }
    };

    final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(announceableTorrent.getHexInfoHash());

    assertEquals(trackedTorrent.getPeers().size(), 1);

    final TrackedPeer trackedPeer = trackedTorrent.getPeers().values().iterator().next();

    assertEquals(trackedPeer.getUploaded(), 0);
    assertEquals(trackedPeer.getDownloaded(), 0);
    assertEquals(trackedPeer.getLeft(), fileSize);

    Piece piece = sharedTorrent.getPiece(1);
    sharedTorrent.handlePieceCompleted(null, piece);
    sharedTorrent.markCompleted(piece);

    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return countOfTrackerResponses.get() >= 2;
      }
    };
    int downloaded = 512 * 1024;//one piece
    assertEquals(trackedPeer.getUploaded(), 0);
    assertEquals(trackedPeer.getDownloaded(), downloaded);
    assertEquals(trackedPeer.getLeft(), fileSize - downloaded);
  }

  public void no_full_seeder_test() throws IOException, URISyntaxException, InterruptedException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48 * 1024; // lower piece size to reduce disk usage
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

  @Test(enabled = false)
  public void corrupted_seeder_repair() throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48 * 1024; // lower piece size to reduce disk usage
    final int numSeeders = 6;
    final int piecesCount = numSeeders + 7;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);

      createMultipleSeedersWithDifferentPieces(baseFile, piecesCount, pieceSize, numSeeders, clientsList);
      String baseMD5 = getFileMD5(baseFile, md5);
      final Client firstClient = clientsList.get(0);

      new WaitFor(10 * 1000) {
        @Override
        protected boolean condition() {
          return firstClient.getTorrentsStorage().activeTorrents().size() >= 1;
        }
      };

      final SharedTorrent torrent = firstClient.getTorrents().iterator().next();
      final File file = new File(torrent.getParentFile(), TorrentUtils.getTorrentFileNames(torrent).get(0));
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
      final WaitFor waitFor = new WaitFor(60 * 1000) {
        @Override
        protected boolean condition() {
          for (Client client : clientsList) {
            final SharedTorrent next = client.getTorrents().iterator().next();
            if (next.getCompletedPieces().cardinality() < next.getPieceCount() - 1) {
              return false;
            }
          }
          return true;
        }
      };

      if (!waitFor.isMyResult()) {
        fail("All seeders didn't get their files");
      }
      Thread.sleep(10 * 1000);
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

  public void testThatTorrentsHaveLazyInitAndRemovingAfterDownload()
          throws IOException, InterruptedException, NoSuchAlgorithmException, URISyntaxException {
    final Client seeder = createClient();
    File tempFile = tempFiles.createTempFile(100 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    Torrent torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);
    seeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParentFile().getAbsolutePath(),
            true, false);

    final Client leecher = createClient();
    File downloadDir = tempFiles.createTempDir();
    leecher.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());
    seeder.start(InetAddress.getLocalHost());

    assertEquals(1, seeder.getTorrentsStorage().announceableTorrents().size());
    assertEquals(0, seeder.getTorrentsStorage().activeTorrents().size());
    assertEquals(0, leecher.getTorrentsStorage().activeTorrents().size());

    leecher.start(InetAddress.getLocalHost());

    WaitFor waitFor = new WaitFor(10 * 1000) {

      @Override
      protected boolean condition() {
        return seeder.getTorrentsStorage().activeTorrents().size() == 1 &&
                leecher.getTorrentsStorage().activeTorrents().size() == 1;
      }
    };

    assertTrue(waitFor.isMyResult(), "Torrent was not successfully initialized");

    assertEquals(1, seeder.getTorrentsStorage().activeTorrents().size());
    assertEquals(1, leecher.getTorrentsStorage().activeTorrents().size());

    waitForFileInDir(downloadDir, tempFile.getName());
    assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));

    waitFor = new WaitFor(10 * 1000) {

      @Override
      protected boolean condition() {
        return seeder.getTorrentsStorage().activeTorrents().size() == 0 &&
                leecher.getTorrentsStorage().activeTorrents().size() == 0;
      }
    };

    assertTrue(waitFor.isMyResult(), "Torrent was not successfully removed");

    assertEquals(0, seeder.getTorrentsStorage().activeTorrents().size());
    assertEquals(0, leecher.getTorrentsStorage().activeTorrents().size());

  }

  public void corrupted_seeder() throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48 * 1024; // lower piece size to reduce disk usage
    final int piecesCount = 35;

    final List<Client> clientsList;
    clientsList = new ArrayList<Client>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      final File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);
      final File badFile = tempFiles.createTempFile(piecesCount * pieceSize);

      final Client client2 = createAndStartClient();
      final File client2Dir = tempFiles.createTempDir();
      final File client2File = new File(client2Dir, baseFile.getName());
      FileUtils.copyFile(badFile, client2File);

      final Torrent torrent = TorrentCreator.create(baseFile, null, this.tracker.getAnnounceURI(), null, "Test", pieceSize);
      final File torrentFile = tempFiles.createTempFile();
      saveTorrent(torrent, torrentFile);

      client2.addTorrent(torrentFile.getAbsolutePath(), client2Dir.getAbsolutePath());

      final String baseMD5 = getFileMD5(baseFile, md5);

      final Client leech = createAndStartClient();
      final File leechDestDir = tempFiles.createTempDir();
      final AtomicReference<Exception> thrownException = new AtomicReference<Exception>();
      final Thread th = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            leech.downloadUninterruptibly(torrentFile.getAbsolutePath(), leechDestDir.getAbsolutePath(), 7);
          } catch (Exception e) {
            thrownException.set(e);
            throw new RuntimeException(e);
          }
        }
      });
      th.start();
      final WaitFor waitFor = new WaitFor(30 * 1000) {
        @Override
        protected boolean condition() {
          return th.getState() == Thread.State.TERMINATED;
        }
      };

      final Map<Thread, StackTraceElement[]> allStackTraces = Thread.getAllStackTraces();
      for (Map.Entry<Thread, StackTraceElement[]> entry : allStackTraces.entrySet()) {
        System.out.printf("%s:%n", entry.getKey().getName());
        for (StackTraceElement elem : entry.getValue()) {
          System.out.println(elem.toString());
        }
      }

      assertTrue(waitFor.isMyResult());
      assertNotNull(thrownException.get());
      assertTrue(thrownException.get().getMessage().contains("Unable to download torrent completely"));

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
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);

    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seeder.start(InetAddress.getLocalHost());

    downloadAndStop(torrent, 15 * 1000, createClient());
    Thread.sleep(2 * 1000);
    assertTrue(dwnlFile.exists() && dwnlFile.isFile());
    final boolean delete = dwnlFile.delete();
    assertTrue(delete && !dwnlFile.exists());
  }

  public void download_many_times() throws InterruptedException, NoSuchAlgorithmException, IOException {
    Client seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);

    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seeder.start(InetAddress.getLocalHost());

    for (int i = 0; i < 5; i++) {
      downloadAndStop(torrent, 250 * 1000, createClient());
      Thread.sleep(3 * 1000);
    }
  }

  public void testConnectToAllDiscoveredPeers() throws Exception {
    tracker.setAcceptForeignTorrents(true);

    final ExecutorService executorService = Executors.newFixedThreadPool(8);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    Client leecher = new Client(executorService, validatorES) {
      @Override
      public void stop() {
        super.stop();
        executorService.shutdownNow();
        validatorES.shutdownNow();
      }
    };
    leecher.setMaxInConnectionsCount(10);
    leecher.setMaxOutConnectionsCount(10);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 34);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);

    final String hexInfoHash = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
    final List<ServerSocket> serverSockets = new ArrayList<ServerSocket>();

    final int startPort = 6885;
    int port = startPort;
    PeerUID[] peerUids = new PeerUID[]{
            new PeerUID(new InetSocketAddress("127.0.0.1", port++), hexInfoHash),
            new PeerUID(new InetSocketAddress("127.0.0.1", port++), hexInfoHash),
            new PeerUID(new InetSocketAddress("127.0.0.1", port), hexInfoHash)
    };
    final ExecutorService es = Executors.newSingleThreadExecutor();
    try {
      leecher.start(InetAddress.getLocalHost());

      WaitFor waitFor = new WaitFor(5000) {
        @Override
        protected boolean condition() {
          return tracker.getTrackedTorrent(hexInfoHash) != null;
        }
      };

      assertTrue(waitFor.isMyResult());

      final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(hexInfoHash);
      Map<PeerUID, TrackedPeer> trackedPeerMap = new HashMap<PeerUID, TrackedPeer>();

      port = startPort;
      for (PeerUID uid : peerUids) {
        trackedPeerMap.put(uid, new TrackedPeer(trackedTorrent, "127.0.0.1", port, ByteBuffer.wrap("id".getBytes(Torrent.BYTE_ENCODING))));
        serverSockets.add(new ServerSocket(port));
        port++;
      }

      trackedTorrent.getPeers().putAll(trackedPeerMap);

      //wait until all server sockets accept connection from leecher
      for (final ServerSocket ss : serverSockets) {
        final Future<?> future = es.submit(new Runnable() {
          @Override
          public void run() {
            try {
              final Socket socket = ss.accept();
              socket.close();
            } catch (IOException e) {
              throw new RuntimeException("can not accept connection");
            }
          }
        });
        try {
          future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
          fail("get execution exception on accept connection", e);
        } catch (TimeoutException e) {
          fail("not received connection from leecher in specified timeout", e);
        }
      }

    } finally {
      for (ServerSocket ss : serverSockets) {
        try {
          ss.close();
        } catch (IOException e) {
          fail("can not close server socket", e);
        }
      }
      es.shutdown();
      leecher.stop();
    }
  }

  public void download_io_error() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 34);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);

    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seeder.start(InetAddress.getLocalHost());

    final AtomicInteger interrupts = new AtomicInteger(0);
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final Client leech = new Client(es, validatorES) {
      @Override
      public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
        super.handlePieceCompleted(peer, piece);
        if (piece.getIndex() % 4 == 0 && interrupts.incrementAndGet() <= 2) {
          peer.unbind(true);
        }
      }

      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);
        es.shutdown();
        validatorES.shutdown();
      }
    };
    //manually add leech here for graceful shutdown.
    clientList.add(leech);
    downloadAndStop(torrent, 45 * 1000, leech);
    Thread.sleep(2 * 1000);
  }

  public void download_uninterruptibly_positive() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent(), true, false);
    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), 10);
  }

  public void download_uninterruptibly_negative() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAcceptForeignTorrents(true);
    final AtomicInteger downloadedPiecesCount = new AtomicInteger(0);
    final Client seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent(), true, false);
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final Client leecher = new Client(es, validatorES) {
      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);
        es.shutdown();
        validatorES.shutdown();
      }

      @Override
      public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
        super.handlePieceCompleted(peer, piece);
        if (downloadedPiecesCount.incrementAndGet() > 10) {
          seeder.stop();
        }
      }
    };
    clientList.add(leecher);
    leecher.start(InetAddress.getLocalHost());
    final File destDir = tempFiles.createTempDir();
    try {
      leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), destDir.getAbsolutePath(), 5);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (IOException ex) {
      // ensure .part was deleted:
      assertEquals(0, destDir.list().length);
    }

  }

  public void download_uninterruptibly_timeout() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    final AtomicInteger piecesDownloaded = new AtomicInteger(0);
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    Client leecher = new Client(es, validatorES) {
      @Override
      public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
        piecesDownloaded.incrementAndGet();
        try {
          Thread.sleep(piecesDownloaded.get() * 500);
        } catch (InterruptedException e) {

        }
      }

      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);
        es.shutdown();
        validatorES.shutdown();
      }
    };
    clientList.add(leecher);
    leecher.start(InetAddress.getLocalHost());
    try {
      leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), 5);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (IOException ex) {
    }
  }

  public void canStartAndStopClientTwice() throws Exception {
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final Client client = new Client(es, validatorES);
    clientList.add(client);
    try {
      client.start(InetAddress.getLocalHost());
      client.stop();
      client.start(InetAddress.getLocalHost());
      client.stop();
    } finally {
      es.shutdown();
      validatorES.shutdown();
    }
  }

  public void peer_dies_during_download() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAnnounceInterval(5);
    final Client seed1 = createClient();
    final Client seed2 = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 240);
    final Torrent torrent = TorrentCreator.create(dwnlFile, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seed1.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seed1.start(InetAddress.getLocalHost());
    seed1.setAnnounceInterval(5);
    seed2.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seed2.start(InetAddress.getLocalHost());
    seed2.setAnnounceInterval(5);

    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    leecher.setAnnounceInterval(5);
    final ExecutorService service = Executors.newFixedThreadPool(1);
    final Future<?> future = service.submit(new Runnable() {
      @Override
      public void run() {
        try {
          Thread.sleep(5 * 1000);
          seed1.removeTorrent(torrent);
          Thread.sleep(3 * 1000);
          seed1.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
          seed2.removeTorrent(torrent);
        } catch (InterruptedException e) {
          e.printStackTrace();
        } catch (NoSuchAlgorithmException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });
    try {
      leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), 60,
              0, new AtomicBoolean(), 10);
    } finally {
      future.cancel(true);
      service.shutdown();
    }
  }

  public void testThatProgressListenerInvoked() throws Exception {
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent(), true, false);
    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final AtomicInteger pieceLoadedInvocationCount = new AtomicInteger();
    leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(),
            10, 1, new AtomicBoolean(false), 5000,
            new DownloadProgressListener() {
              @Override
              public void pieceLoaded(int pieceIndex, int pieceSize) {
                pieceLoadedInvocationCount.incrementAndGet();
              }
            });
    assertEquals(pieceLoadedInvocationCount.get(), torrent.getPieceCount());
  }

  public void interrupt_download() throws IOException, InterruptedException, NoSuchAlgorithmException {
    tracker.setAcceptForeignTorrents(true);
    final Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 60);
    final Torrent torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent(), true, false);
    final Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final AtomicBoolean interrupted = new AtomicBoolean();
    final Thread th = new Thread() {
      @Override
      public void run() {
        try {
          leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), 30);
        } catch (IOException e) {
          e.printStackTrace();
        } catch (InterruptedException e) {
          interrupted.set(true);
          return;
        } catch (NoSuchAlgorithmException e) {
          e.printStackTrace();
          return;
        }
      }
    };
    th.start();
    Thread.sleep(100);
    th.interrupt();
    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return !th.isAlive();
      }
    };

    assertTrue(interrupted.get());
  }

  public void test_connect_to_unknown_host() throws InterruptedException, NoSuchAlgorithmException, IOException {
    final File torrent = new File("src/test/resources/torrents/file1.jar.torrent");
    final TrackedTorrent tt = TrackedTorrent.load(torrent);
    final Client seeder = createAndStartClient();
    final Client leecher = createAndStartClient();
    final TrackedTorrent announce = tracker.announce(tt);
    final Random random = new Random();
    final File leechFolder = tempFiles.createTempDir();

    for (int i = 0; i < 40; i++) {
      byte[] data = new byte[20];
      random.nextBytes(data);
      announce.addPeer(new TrackedPeer(tt, "my_unknown_and_unreachablehost" + i, 6881, ByteBuffer.wrap(data)));
    }
    File torrentFile = new File(TEST_RESOURCES + "/torrents", "file1.jar.torrent");
    File parentFiles = new File(TEST_RESOURCES + "/parentFiles");
    seeder.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath(), true, false);
    leecher.addTorrent(torrentFile.getAbsolutePath(), leechFolder.getAbsolutePath());
    waitForFileInDir(leechFolder, "file1.jar");
  }

  public void test_seeding_does_not_change_file_modification_date() throws IOException, InterruptedException, NoSuchAlgorithmException {
    File srcFile = tempFiles.createTempFile(1024);
    long time = srcFile.lastModified();

    Thread.sleep(1000);

    Client seeder = createAndStartClient();

    final Torrent torrent = TorrentCreator.create(srcFile, null, tracker.getAnnounceURI(), "Test");

    File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.addTorrent(torrentFile.getAbsolutePath(), srcFile.getParent(), true, false);

    final File downloadDir = tempFiles.createTempDir();
    Client leech = createClient();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());

    leech.start(InetAddress.getLocalHost());

    waitForFileInDir(downloadDir, srcFile.getName());

    assertEquals(time, srcFile.lastModified());
  }

  private void downloadAndStop(Torrent torrent, long timeout, final Client leech) throws IOException, NoSuchAlgorithmException, InterruptedException {
    final File tempDir = tempFiles.createTempDir();
    File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    leech.addTorrent(torrentFile.getAbsolutePath(), tempDir.getAbsolutePath());
    leech.start(InetAddress.getLocalHost());

    waitForFileInDir(tempDir, torrent.getFilenames().get(0));

    leech.stop();
  }

  private void validateMultipleClientsResults(final List<Client> clientsList, MessageDigest md5, final File baseFile, String baseMD5) throws IOException {
    final WaitFor waitFor = new WaitFor(75 * 1000) {
      @Override
      protected boolean condition() {
        boolean retval = true;
        for (Client client : clientsList) {
          if (!retval) return false;
          final AnnounceableFileTorrent torrent = (AnnounceableFileTorrent) client.getTorrentsStorage().announceableTorrents().iterator().next();
          File downloadedFile = new File(torrent.getDownloadDirPath(), baseFile.getName());
          retval = downloadedFile.isFile();
        }
        return retval;
      }
    };

    assertTrue(waitFor.isMyResult(), "All seeders didn't get their files");
    // check file contents here:
    for (Client client : clientsList) {
      final AnnounceableFileTorrent torrent = (AnnounceableFileTorrent) client.getTorrentsStorage().announceableTorrents().iterator().next();
      final File file = new File(torrent.getDownloadDirPath(), baseFile.getName());
      assertEquals(baseMD5, getFileMD5(file, md5), String.format("MD5 hash is invalid. C:%s, O:%s ",
              file.getAbsolutePath(), baseFile.getAbsolutePath()));
    }
  }

  public void testManySeeders() throws Exception {
    File artifact = tempFiles.createTempFile(300 * 1024 * 1024);
    int seedersCount = 4;
    Torrent torrent = TorrentCreator.create(artifact, this.tracker.getAnnounceURI(), "test");
    File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    for (int i = 0; i < seedersCount; i++) {
      Client seeder = createClient();
      seeder.addTorrent(torrentFile.getAbsolutePath(), artifact.getParent(), true, false);
      seeder.start(InetAddress.getLocalHost());
    }

    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    leecher.downloadUninterruptibly(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath(), 10);
  }

  private void createMultipleSeedersWithDifferentPieces(File baseFile, int piecesCount, int pieceSize, int numSeeders,
                                                        List<Client> clientList) throws IOException, InterruptedException, NoSuchAlgorithmException, URISyntaxException {

    List<byte[]> piecesList = new ArrayList<byte[]>(piecesCount);
    FileInputStream fin = new FileInputStream(baseFile);
    for (int i = 0; i < piecesCount; i++) {
      byte[] piece = new byte[pieceSize];
      fin.read(piece);
      piecesList.add(piece);
    }
    fin.close();

    final long torrentFileLength = baseFile.length();
    Torrent torrent = TorrentCreator.create(baseFile, null, this.tracker.getAnnounceURI(), null, "Test", pieceSize);
    File torrentFile = new File(baseFile.getParentFile(), baseFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);


    for (int i = 0; i < numSeeders; i++) {
      final File baseDir = tempFiles.createTempDir();
      final File seederPiecesFile = new File(baseDir, baseFile.getName());
      RandomAccessFile raf = new RandomAccessFile(seederPiecesFile, "rw");
      raf.setLength(torrentFileLength);
      for (int pieceIdx = i; pieceIdx < piecesCount; pieceIdx += numSeeders) {
        raf.seek(pieceIdx * pieceSize);
        raf.write(piecesList.get(pieceIdx));
      }
      Client client = createClient(" client idx " + i);
      clientList.add(client);
      client.addTorrent(torrentFile.getAbsolutePath(), baseDir.getAbsolutePath());
      client.start(InetAddress.getLocalHost());
    }
  }

  private String getFileMD5(File file, MessageDigest digest) throws IOException {
    DigestInputStream dIn = new DigestInputStream(new FileInputStream(file), digest);
    while (dIn.read() >= 0) ;
    return dIn.getMessageDigest().toString();
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
    for (Client client : clientList) {
      client.stop();
    }
    stopTracker();
    tempFiles.cleanup();
  }

  private void startTracker() throws IOException {
    this.tracker = new Tracker(6969);
    tracker.setAnnounceInterval(5);
    this.tracker.start(true);
  }

  private Client createAndStartClient() throws IOException, NoSuchAlgorithmException, InterruptedException {
    Client client = createClient();
    client.start(InetAddress.getLocalHost());
    return client;
  }

  private Client createClient(String name) throws IOException, NoSuchAlgorithmException, InterruptedException {
    final Client client = clientFactory.getClient(name);
    clientList.add(client);
    return client;
  }

  private Client createClient() throws IOException, NoSuchAlgorithmException, InterruptedException {
    return createClient("");
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
