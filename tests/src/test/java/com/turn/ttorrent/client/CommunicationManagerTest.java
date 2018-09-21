package com.turn.ttorrent.client;

import com.turn.ttorrent.*;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.client.storage.EmptyPieceStorageFactory;
import com.turn.ttorrent.client.storage.FileStorage;
import com.turn.ttorrent.client.storage.FullyPieceStorageFactory;
import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.common.*;
import com.turn.ttorrent.common.protocol.PeerMessage;
import com.turn.ttorrent.network.FirstAvailableChannel;
import com.turn.ttorrent.network.ServerChannelRegister;
import com.turn.ttorrent.tracker.TrackedPeer;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.BasicConfigurator;
import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.ByteChannel;
import java.nio.channels.ClosedByInterruptException;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;
import java.util.zip.Checksum;

import static com.turn.ttorrent.CommunicationManagerFactory.DEFAULT_POOL_SIZE;
import static com.turn.ttorrent.tracker.Tracker.ANNOUNCE_URL;
import static org.testng.Assert.*;

/**
 * @author Sergey.Pak
 * Date: 7/26/13
 * Time: 2:32 PM
 */
@Test(timeOut = 600000)
public class CommunicationManagerTest {

  private CommunicationManagerFactory communicationManagerFactory;

  private List<CommunicationManager> communicationManagerList;
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;

  public CommunicationManagerTest() {
    communicationManagerFactory = new CommunicationManagerFactory();
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
  }

  @BeforeMethod
  public void setUp() throws IOException {
    tempFiles = new TempFiles();
    communicationManagerList = new ArrayList<CommunicationManager>();
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    startTracker();
  }

  private void saveTorrent(TorrentMetadata torrent, File file) throws IOException {
    FileOutputStream fos = new FileOutputStream(file);
    fos.write(new TorrentSerializer().serialize(torrent));
    fos.close();
  }

  public void testThatSeederIsNotReceivedHaveMessages() throws Exception {
    final ExecutorService workerES = Executors.newFixedThreadPool(10);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final AtomicBoolean isSeederReceivedHaveMessage = new AtomicBoolean(false);
    CommunicationManager seeder = new CommunicationManager(workerES, validatorES) {

      @Override
      public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel, String clientIdentifier, int clientVersion) {
        return new SharingPeer(host, port, peerId, torrent, getConnectionManager(), this, channel, "TO", 1234) {
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
    communicationManagerList.add(seeder);

    File tempFile = tempFiles.createTempFile(100 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    TorrentMetadata torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    seeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent());
    seeder.start(InetAddress.getLocalHost());

    waitForSeederIsAnnounsedOnTracker(torrent.getHexInfoHash());

    CommunicationManager leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
    waitDownloadComplete(torrentManager, 15);
    assertFalse(isSeederReceivedHaveMessage.get());
  }

  private void waitDownloadComplete(TorrentManager torrentManager, int timeoutSec) throws InterruptedException {
    final Semaphore semaphore = new Semaphore(0);
    TorrentListenerWrapper listener = new TorrentListenerWrapper() {
      @Override
      public void downloadComplete() {
        semaphore.release();
      }
    };
    try {
      torrentManager.addListener(listener);
      boolean res = semaphore.tryAcquire(timeoutSec, TimeUnit.SECONDS);
      if (!res) throw new RuntimeException("Unable to download file in " + timeoutSec + " seconds");
    } finally {
      torrentManager.removeListener(listener);
    }
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
  public void download_multiple_files() throws IOException, InterruptedException, URISyntaxException {
    int numFiles = 50;
    this.tracker.setAcceptForeignTorrents(true);

    final File srcDir = tempFiles.createTempDir();
    final File downloadDir = tempFiles.createTempDir();

    CommunicationManager seeder = createClient("seeder");
    seeder.start(InetAddress.getLocalHost());
    CommunicationManager leech = null;


    try {
      URL announce = new URL("http://127.0.0.1:6969/announce");
      URI announceURI = announce.toURI();
      final Set<String> names = new HashSet<String>();
      List<File> filesToShare = new ArrayList<File>();
      for (int i = 0; i < numFiles; i++) {
        File tempFile = tempFiles.createTempFile(513 * 1024);
        File srcFile = new File(srcDir, tempFile.getName());
        assertTrue(tempFile.renameTo(srcFile));

        TorrentMetadata torrent = TorrentCreator.create(srcFile, announceURI, "Test");
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
      leech.start(new InetAddress[]{InetAddress.getLocalHost()}, 5, null, new SelectorFactoryImpl());
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

  public void testHungSeeder() throws Exception {
    this.tracker.setAcceptForeignTorrents(true);

    File tempFile = tempFiles.createTempFile(500 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    TorrentMetadata torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    CommunicationManager goodSeeder = createClient();
    goodSeeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent(), FullyPieceStorageFactory.INSTANCE);

    final ExecutorService es = Executors.newFixedThreadPool(10);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    CommunicationManager hungSeeder = new CommunicationManager(es, validatorES) {
      @Override
      public void stop() {
        super.stop();
        es.shutdownNow();
        validatorES.shutdownNow();
      }

      @Override
      public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel, String clientIdentifier, int clientVersion) {
        return new SharingPeer(host, port, peerId, torrent, getConnectionManager(), this, channel, clientIdentifier, clientVersion) {
          @Override
          public void handleMessage(PeerMessage msg) {
            if (msg instanceof PeerMessage.RequestMessage) {
              return;
            }
            super.handleMessage(msg);
          }
        };
      }
    };
    hungSeeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent(), FullyPieceStorageFactory.INSTANCE);

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createClient();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath(), EmptyPieceStorageFactory.INSTANCE);

    try {
      hungSeeder.start(InetAddress.getLocalHost());
      goodSeeder.start(InetAddress.getLocalHost());
      leech.start(InetAddress.getLocalHost());

      waitForFileInDir(downloadDir, tempFile.getName());
      assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
    } finally {
      goodSeeder.stop();
      leech.stop();
    }
  }

  public void large_file_download() throws IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    File tempFile = tempFiles.createTempFile(201 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    TorrentMetadata torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    CommunicationManager seeder = createClient();
    seeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent(), FullyPieceStorageFactory.INSTANCE);

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createClient();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath(), EmptyPieceStorageFactory.INSTANCE);

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

  @Test()
  public void testManyLeechers() throws IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    File tempFile = tempFiles.createTempFile(400 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    TorrentMetadata torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    CommunicationManager seeder = createClient();
    seeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent(), FullyPieceStorageFactory.INSTANCE);

    List<Map.Entry<CommunicationManager, File>> leechers = new ArrayList<Map.Entry<CommunicationManager, File>>();
    for (int i = 0; i < 4; i++) {
      final File downloadDir = tempFiles.createTempDir();
      CommunicationManager leech = createClient();
      leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath(), EmptyPieceStorageFactory.INSTANCE);
      leechers.add(new AbstractMap.SimpleEntry<CommunicationManager, File>(leech, downloadDir));
    }

    try {
      seeder.start(InetAddress.getLocalHost());
      for (Map.Entry<CommunicationManager, File> entry : leechers) {
        entry.getKey().start(InetAddress.getLocalHost());
      }

      for (Map.Entry<CommunicationManager, File> leecher : leechers) {
        File downloadDir = leecher.getValue();
        waitForFileInDir(downloadDir, tempFile.getName());
        assertFilesEqual(tempFile, new File(downloadDir, tempFile.getName()));
      }
    } finally {
      seeder.stop();
      for (Map.Entry<CommunicationManager, File> e : leechers) {
        e.getKey().stop();
      }
    }
  }

  @Test(enabled = false)
  public void endgameModeTest() throws Exception {
    this.tracker.setAcceptForeignTorrents(true);
    final int numSeeders = 2;
    List<CommunicationManager> seeders = new ArrayList<CommunicationManager>();
    final AtomicInteger skipPiecesCount = new AtomicInteger(1);
    for (int i = 0; i < numSeeders; i++) {
      final ExecutorService es = Executors.newFixedThreadPool(10);
      final ExecutorService validatorES = Executors.newFixedThreadPool(4);
      final CommunicationManager seeder = new CommunicationManager(es, validatorES) {
        @Override
        public void stop() {
          super.stop();
          es.shutdownNow();
          validatorES.shutdownNow();
        }

        @Override
        public SharingPeer createSharingPeer(String host, int port, ByteBuffer peerId, SharedTorrent torrent, ByteChannel channel, String clientIdentifier, int clientVersion) {
          return new SharingPeer(host, port, peerId, torrent, getConnectionManager(), this, channel, "TO", 1234) {
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
      communicationManagerList.add(seeder);
    }
    File tempFile = tempFiles.createTempFile(1024 * 20 * 1024);

    TorrentMetadata torrent = TorrentCreator.create(tempFile, this.tracker.getAnnounceURI(), "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    for (int i = 0; i < numSeeders; i++) {
      CommunicationManager communicationManager = seeders.get(i);
      communicationManager.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent());
      communicationManager.start(InetAddress.getLocalHost());
    }

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createClient();
    leech.start(InetAddress.getLocalHost());
    TorrentManager torrentManager = leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getParent());
    waitDownloadComplete(torrentManager, 20);

    waitForFileInDir(downloadDir, tempFile.getName());

  }


  public void more_than_one_seeder_for_same_torrent() throws IOException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);
    assertEquals(0, this.tracker.getTrackedTorrents().size());

    final int numSeeders = 5;
    List<CommunicationManager> seeders = new ArrayList<CommunicationManager>();
    for (int i = 0; i < numSeeders; i++) {
      seeders.add(createClient());
    }

    try {
      File tempFile = tempFiles.createTempFile(100 * 1024);

      TorrentMetadata torrent = TorrentCreator.create(tempFile, this.tracker.getAnnounceURI(), "Test");
      File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
      saveTorrent(torrent, torrentFile);

      for (int i = 0; i < numSeeders; i++) {
        CommunicationManager communicationManager = seeders.get(i);
        communicationManager.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParent());
        communicationManager.start(InetAddress.getLocalHost());
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
      for (CommunicationManager communicationManager : seeders) {
        communicationManager.stop();
      }
    }

  }

  public void testThatDownloadStatisticProvidedToTracker() throws Exception {
    final ExecutorService executorService = Executors.newFixedThreadPool(8);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final AtomicInteger countOfTrackerResponses = new AtomicInteger(0);
    CommunicationManager leecher = new CommunicationManager(executorService, validatorES) {
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

    communicationManagerList.add(leecher);

    final int fileSize = 2 * 1025 * 1024;
    File tempFile = tempFiles.createTempFile(fileSize);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    TorrentMetadata torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);

    String hash = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath()).getHexInfoHash();
    leecher.start(InetAddress.getLocalHost());
    final LoadedTorrent announceableTorrent = leecher.getTorrentsStorage().getLoadedTorrent(hash);

    final SharedTorrent sharedTorrent = leecher.getTorrentsStorage().putIfAbsentActiveTorrent(announceableTorrent.getTorrentHash().getHexInfoHash(),
            leecher.getTorrentLoader().loadTorrent(announceableTorrent));

    sharedTorrent.init();

    new WaitFor(10 * 1000) {
      @Override
      protected boolean condition() {
        return countOfTrackerResponses.get() == 1;
      }
    };

    final TrackedTorrent trackedTorrent = tracker.getTrackedTorrent(announceableTorrent.getTorrentHash().getHexInfoHash());

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

    final List<CommunicationManager> clientsList;
    clientsList = new ArrayList<CommunicationManager>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File tempFile = tempFiles.createTempFile(piecesCount * pieceSize);
      List<File> targetFiles = new ArrayList<File>();

      String hash = createMultipleSeedersWithDifferentPieces(tempFile, piecesCount, pieceSize, numSeeders, clientsList, targetFiles);
      String baseMD5 = getFileMD5(tempFile, md5);
      assertEquals(numSeeders, targetFiles.size());

      validateMultipleClientsResults(clientsList, md5, tempFile, baseMD5, hash, targetFiles);

    } finally {
      for (CommunicationManager communicationManager : clientsList) {
        communicationManager.stop();
      }
    }
  }

  @Test(enabled = false)
  public void corrupted_seeder_repair() throws IOException, URISyntaxException, InterruptedException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48 * 1024; // lower piece size to reduce disk usage
    final int numSeeders = 6;
    final int piecesCount = numSeeders + 7;

    final List<CommunicationManager> clientsList;
    clientsList = new ArrayList<CommunicationManager>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);
      List<File> targetFiles = new ArrayList<File>();

      String hash = createMultipleSeedersWithDifferentPieces(baseFile, piecesCount, pieceSize, numSeeders, clientsList, targetFiles);
      assertEquals(numSeeders, targetFiles.size());
      String baseMD5 = getFileMD5(baseFile, md5);
      final CommunicationManager firstCommunicationManager = clientsList.get(0);

      new WaitFor(10 * 1000) {
        @Override
        protected boolean condition() {
          return firstCommunicationManager.getTorrentsStorage().activeTorrents().size() >= 1;
        }
      };

      final SharedTorrent torrent = firstCommunicationManager.getTorrents().iterator().next();
      final File file = new File(targetFiles.get(0).getParentFile(), TorrentUtils.getTorrentFileNames(torrent).get(0));
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
          for (CommunicationManager client : clientsList) {
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
      validateMultipleClientsResults(clientsList, md5, baseFile, baseMD5, hash, targetFiles);

    } finally {
      for (CommunicationManager communicationManager : clientsList) {
        communicationManager.stop();
      }
    }
  }

  public void testThatTorrentsHaveLazyInitAndRemovingAfterDownload()
          throws IOException, InterruptedException, URISyntaxException {
    final CommunicationManager seeder = createClient();
    File tempFile = tempFiles.createTempFile(100 * 1025 * 1024);
    URL announce = new URL("http://127.0.0.1:6969/announce");
    URI announceURI = announce.toURI();

    TorrentMetadata torrent = TorrentCreator.create(tempFile, announceURI, "Test");
    File torrentFile = new File(tempFile.getParentFile(), tempFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);
    seeder.addTorrent(torrentFile.getAbsolutePath(), tempFile.getParentFile().getAbsolutePath());

    final CommunicationManager leecher = createClient();
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

  public void corrupted_seeder() throws IOException, InterruptedException, NoSuchAlgorithmException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48 * 1024; // lower piece size to reduce disk usage
    final int piecesCount = 35;

    final List<CommunicationManager> clientsList;
    clientsList = new ArrayList<CommunicationManager>(piecesCount);

    final MessageDigest md5 = MessageDigest.getInstance("MD5");

    try {
      final File baseFile = tempFiles.createTempFile(piecesCount * pieceSize);
      final File badFile = tempFiles.createTempFile(piecesCount * pieceSize);

      final CommunicationManager communicationManager2 = createAndStartClient();
      final File client2Dir = tempFiles.createTempDir();
      final File client2File = new File(client2Dir, baseFile.getName());
      FileUtils.copyFile(badFile, client2File);

      final TorrentMetadata torrent = TorrentCreator.create(baseFile, null, this.tracker.getAnnounceURI(), null, "Test", pieceSize);
      final File torrentFile = tempFiles.createTempFile();
      saveTorrent(torrent, torrentFile);

      communicationManager2.addTorrent(torrentFile.getAbsolutePath(), client2Dir.getAbsolutePath());

      final CommunicationManager leech = createAndStartClient();
      final File leechDestDir = tempFiles.createTempDir();
      final AtomicReference<Exception> thrownException = new AtomicReference<Exception>();
      final Thread th = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            TorrentManager torrentManager = leech.addTorrent(torrentFile.getAbsolutePath(), leechDestDir.getAbsolutePath());
            waitDownloadComplete(torrentManager, 10);
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
      assertTrue(thrownException.get().getMessage().contains("Unable to download"));

    } finally {
      for (CommunicationManager communicationManager : clientsList) {
        communicationManager.stop();
      }
    }
  }

  public void unlock_file_when_no_leechers() throws InterruptedException, IOException {
    CommunicationManager seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
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

  public void download_many_times() throws InterruptedException, IOException {
    CommunicationManager seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
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
    CommunicationManager leecher = new CommunicationManager(executorService, validatorES) {
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
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);

    final String hexInfoHash = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath()).getHexInfoHash();
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
        trackedPeerMap.put(uid, new TrackedPeer(trackedTorrent, "127.0.0.1", port, ByteBuffer.wrap("id".getBytes(Constants.BYTE_ENCODING))));
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

  public void download_io_error() throws InterruptedException, IOException {
    tracker.setAcceptForeignTorrents(true);
    CommunicationManager seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 34);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);

    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seeder.start(InetAddress.getLocalHost());

    final AtomicInteger interrupts = new AtomicInteger(0);
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final CommunicationManager leech = new CommunicationManager(es, validatorES) {
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
    communicationManagerList.add(leech);
    downloadAndStop(torrent, 45 * 1000, leech);
    Thread.sleep(2 * 1000);
  }

  public void download_uninterruptibly_positive() throws InterruptedException, IOException {
    tracker.setAcceptForeignTorrents(true);
    CommunicationManager seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    CommunicationManager leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
    waitDownloadComplete(torrentManager, 10);
  }

  public void download_uninterruptibly_negative() throws InterruptedException, IOException {
    tracker.setAcceptForeignTorrents(true);
    final AtomicInteger downloadedPiecesCount = new AtomicInteger(0);
    final CommunicationManager seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final CommunicationManager leecher = new CommunicationManager(es, validatorES) {
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
    communicationManagerList.add(leecher);
    leecher.start(InetAddress.getLocalHost());
    final File destDir = tempFiles.createTempDir();
    TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), destDir.getAbsolutePath());
    try {
      waitDownloadComplete(torrentManager, 7);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (RuntimeException ex) {

      LoadedTorrent loadedTorrent = leecher.getTorrentsStorage().getLoadedTorrent(torrentManager.getHexInfoHash());
      loadedTorrent.getPieceStorage().close();

      // delete .part file
      File[] destDirFiles = destDir.listFiles();
      assertNotNull(destDirFiles);
      assertEquals(1, destDirFiles.length);
      File targetFile = destDirFiles[0];
      if (!targetFile.delete()) {
        fail("Unable to remove file " + targetFile);
      }
      // ensure .part was deleted:
      destDirFiles = destDir.listFiles();
      assertNotNull(destDirFiles);
      assertEquals(0, destDirFiles.length);
    }

  }

  public void download_uninterruptibly_timeout() throws InterruptedException, IOException {
    tracker.setAcceptForeignTorrents(true);
    CommunicationManager seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    final AtomicInteger piecesDownloaded = new AtomicInteger(0);
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    CommunicationManager leecher = new CommunicationManager(es, validatorES) {
      @Override
      public void handlePieceCompleted(SharingPeer peer, Piece piece) {
        piecesDownloaded.incrementAndGet();
        try {
          Thread.sleep(piecesDownloaded.get() * 500);
        } catch (InterruptedException ignored) {

        }
      }

      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);
        es.shutdown();
        validatorES.shutdown();
      }
    };
    communicationManagerList.add(leecher);
    leecher.start(InetAddress.getLocalHost());
    try {
      TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
      waitDownloadComplete(torrentManager, 7);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (RuntimeException ignored) {
    }
  }

  public void canStartAndStopClientTwice() throws Exception {
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService validatorES = Executors.newFixedThreadPool(4);
    final CommunicationManager communicationManager = new CommunicationManager(es, validatorES);
    communicationManagerList.add(communicationManager);
    try {
      communicationManager.start(InetAddress.getLocalHost());
      communicationManager.stop();
      communicationManager.start(InetAddress.getLocalHost());
      communicationManager.stop();
    } finally {
      es.shutdown();
      validatorES.shutdown();
    }
  }

  public void peer_dies_during_download() throws InterruptedException, IOException {
    tracker.setAnnounceInterval(5);
    final CommunicationManager seed1 = createClient();
    final CommunicationManager seed2 = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 240);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seed1.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seed1.start(InetAddress.getLocalHost());
    seed1.setAnnounceInterval(5);
    seed2.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    seed2.start(InetAddress.getLocalHost());
    seed2.setAnnounceInterval(5);

    CommunicationManager leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    leecher.setAnnounceInterval(5);
    final ExecutorService service = Executors.newFixedThreadPool(1);
    final Future<?> future = service.submit(new Runnable() {
      @Override
      public void run() {
        try {
          Thread.sleep(5 * 1000);
          seed1.removeTorrent(torrent.getHexInfoHash());
          Thread.sleep(3 * 1000);
          seed1.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
          seed2.removeTorrent(torrent.getHexInfoHash());
        } catch (InterruptedException e) {
          e.printStackTrace();
        } catch (IOException e) {
          e.printStackTrace();
        }
      }
    });
    try {
      TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
      waitDownloadComplete(torrentManager, 60);
    } finally {
      future.cancel(true);
      service.shutdown();
    }
  }

  public void torrentListenersPositiveTest() throws Exception {
    tracker.setAcceptForeignTorrents(true);
    CommunicationManager seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24 + 1);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    CommunicationManager leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final AtomicInteger pieceLoadedInvocationCount = new AtomicInteger();
    final AtomicInteger connectedInvocationCount = new AtomicInteger();
    final Semaphore disconnectedLock = new Semaphore(0);
    TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
    final AtomicLong totalDownloaded = new AtomicLong();
    torrentManager.addListener(new TorrentListenerWrapper() {
      @Override
      public void pieceDownloaded(PieceInformation pieceInformation, PeerInformation peerInformation) {
        totalDownloaded.addAndGet(pieceInformation.getSize());
        pieceLoadedInvocationCount.incrementAndGet();
      }

      @Override
      public void peerConnected(PeerInformation peerInformation) {
        connectedInvocationCount.incrementAndGet();
      }

      @Override
      public void peerDisconnected(PeerInformation peerInformation) {
        disconnectedLock.release();
      }
    });
    waitDownloadComplete(torrentManager, 5);
    assertEquals(pieceLoadedInvocationCount.get(), torrent.getPiecesCount());
    assertEquals(connectedInvocationCount.get(), 1);
    assertEquals(totalDownloaded.get(), dwnlFile.length());
    if (!disconnectedLock.tryAcquire(10, TimeUnit.SECONDS)) {
      fail("connection with seeder must be closed after download");
    }
  }

  public void testClosingPieceStorageWhenDownloading() throws Exception {

    tracker.setAcceptForeignTorrents(true);
    final CommunicationManager seeder = createAndStartClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final PieceStorage seederStorage = FullyPieceStorageFactory.INSTANCE.createStorage(torrent, new FileStorage(dwnlFile, 0, dwnlFile.length()));
    seeder.addTorrent(new TorrentMetadataProvider() {
      @NotNull
      @Override
      public TorrentMetadata getTorrentMetadata() {
        return torrent;
      }
    }, seederStorage);

    CommunicationManager leecher = createAndStartClient();

    final PieceStorage leecherStorage = EmptyPieceStorageFactory.INSTANCE.createStorage(torrent, new FileStorage(tempFiles.createTempFile(), 0, dwnlFile.length()));
    TorrentManager torrentManager = leecher.addTorrent(new TorrentMetadataProvider() {
      @NotNull
      @Override
      public TorrentMetadata getTorrentMetadata() {
        return torrent;
      }
    }, leecherStorage);

    final AtomicReference<Throwable> exceptionHolder = new AtomicReference<Throwable>();
    torrentManager.addListener(new TorrentListenerWrapper() {
      @Override
      public void pieceDownloaded(PieceInformation pieceInformation, PeerInformation peerInformation) {
        try {
          seederStorage.close();
          leecherStorage.close();
        } catch (IOException e) {
          exceptionHolder.set(e);
        }
      }
    });

    waitDownloadComplete(torrentManager, 10);
    Throwable throwable = exceptionHolder.get();
    if (throwable != null) {
      fail("", throwable);
    }
  }

  public void testListenersWithBadSeeder() throws Exception {
    tracker.setAcceptForeignTorrents(true);
    CommunicationManager seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 240);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    RandomAccessFile raf = new RandomAccessFile(dwnlFile, "rw");
    //changing one byte in file. So one piece
    try {
      long pos = dwnlFile.length() / 2;
      raf.seek(pos);
      int oldByte = raf.read();
      raf.seek(pos);
      raf.write(oldByte + 1);
    } finally {
      raf.close();
    }
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent(), FullyPieceStorageFactory.INSTANCE);
    CommunicationManager leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final AtomicInteger pieceLoadedInvocationCount = new AtomicInteger();
    TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
    torrentManager.addListener(new TorrentListenerWrapper() {
      @Override
      public void pieceDownloaded(PieceInformation pieceInformation, PeerInformation peerInformation) {
        pieceLoadedInvocationCount.incrementAndGet();
      }
    });
    try {
      waitDownloadComplete(torrentManager, 15);
      fail("Downloading must be failed because seeder doesn't have valid piece");
    } catch (RuntimeException ignored) {
    }
    assertEquals(pieceLoadedInvocationCount.get(), torrent.getPiecesCount() - 1);
  }

  public void interrupt_download() throws IOException, InterruptedException {
    tracker.setAcceptForeignTorrents(true);
    final CommunicationManager seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 60);
    final TorrentMetadata torrent = TorrentCreator.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    final File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(torrentFile.getAbsolutePath(), dwnlFile.getParent());
    final CommunicationManager leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final AtomicBoolean interrupted = new AtomicBoolean();
    final Thread th = new Thread() {
      @Override
      public void run() {
        try {
          TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(), tempFiles.createTempDir().getAbsolutePath());
          waitDownloadComplete(torrentManager, 30);
        } catch (ClosedByInterruptException e) {
          interrupted.set(true);
        } catch (IOException e) {
          e.printStackTrace();
        } catch (InterruptedException e) {
          interrupted.set(true);
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

  public void test_connect_to_unknown_host() throws InterruptedException, IOException {
    final File torrent = new File("src/test/resources/torrents/file1.jar.torrent");
    final TrackedTorrent tt = TrackedTorrent.load(torrent);
    final CommunicationManager seeder = createAndStartClient();
    final CommunicationManager leecher = createAndStartClient();
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
    seeder.addTorrent(torrentFile.getAbsolutePath(), parentFiles.getAbsolutePath());
    leecher.addTorrent(torrentFile.getAbsolutePath(), leechFolder.getAbsolutePath());
    waitForFileInDir(leechFolder, "file1.jar");
  }

  public void test_seeding_does_not_change_file_modification_date() throws IOException, InterruptedException {
    File srcFile = tempFiles.createTempFile(1024);
    long time = srcFile.lastModified();

    Thread.sleep(1000);

    CommunicationManager seeder = createAndStartClient();

    final TorrentMetadata torrent = TorrentCreator.create(srcFile, null, tracker.getAnnounceURI(), "Test");

    File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    seeder.addTorrent(torrentFile.getAbsolutePath(), srcFile.getParent(), FullyPieceStorageFactory.INSTANCE);

    final File downloadDir = tempFiles.createTempDir();
    CommunicationManager leech = createClient();
    leech.addTorrent(torrentFile.getAbsolutePath(), downloadDir.getAbsolutePath());

    leech.start(InetAddress.getLocalHost());

    waitForFileInDir(downloadDir, srcFile.getName());

    assertEquals(time, srcFile.lastModified());
  }

  private void downloadAndStop(TorrentMetadata torrent, long timeout, final CommunicationManager leech) throws IOException {
    final File tempDir = tempFiles.createTempDir();
    File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    leech.addTorrent(torrentFile.getAbsolutePath(), tempDir.getAbsolutePath());
    leech.start(InetAddress.getLocalHost());

    waitForFileInDir(tempDir, torrent.getFiles().get(0).getRelativePathAsString());

    leech.stop();
  }

  private void validateMultipleClientsResults(final List<CommunicationManager> clientsList,
                                              MessageDigest md5,
                                              final File baseFile,
                                              String baseMD5,
                                              final String hash,
                                              final List<File> targetFiles) throws IOException {

    final WaitFor waitFor = new WaitFor(75 * 1000) {
      @Override
      protected boolean condition() {
        boolean retval = true;
        for (int i = 0; i < clientsList.size(); i++) {
          if (!retval) return false;
          File target = targetFiles.get(i);
          retval = target.isFile();
        }
        return retval;
      }
    };

    assertTrue(waitFor.isMyResult(), "All seeders didn't get their files");
    // check file contents here:
    for (int i = 0; i < clientsList.size(); i++) {
      final LoadedTorrent torrent = communicationManagerList.get(i).getTorrentsStorage().getLoadedTorrent(hash);
      final File file = targetFiles.get(i);
      assertEquals(baseMD5, getFileMD5(file, md5), String.format("MD5 hash is invalid. C:%s, O:%s ",
              file.getAbsolutePath(), baseFile.getAbsolutePath()));
    }
  }

  public void testManySeeders() throws Exception {
    File artifact = tempFiles.createTempFile(256 * 1024 * 1024);
    int seedersCount = 15;
    TorrentMetadata torrent = TorrentCreator.create(artifact, this.tracker.getAnnounceURI(), "test");
    File torrentFile = tempFiles.createTempFile();
    saveTorrent(torrent, torrentFile);
    ServerChannelRegister serverChannelRegister = new FirstAvailableChannel(6881, 10000);
    for (int i = 0; i < seedersCount; i++) {
      CommunicationManager seeder = createClient();
      seeder.addTorrent(torrentFile.getAbsolutePath(), artifact.getParent(), FullyPieceStorageFactory.INSTANCE);
      seeder.start(new InetAddress[]{InetAddress.getLocalHost()},
              Constants.DEFAULT_ANNOUNCE_INTERVAL_SEC,
              null,
              new SelectorFactoryImpl(),
              serverChannelRegister);
    }

    CommunicationManager leecher = createClient();
    leecher.start(new InetAddress[]{InetAddress.getLocalHost()},
            Constants.DEFAULT_ANNOUNCE_INTERVAL_SEC,
            null,
            new SelectorFactoryImpl(),
            serverChannelRegister);
    TorrentManager torrentManager = leecher.addTorrent(torrentFile.getAbsolutePath(),
            tempFiles.createTempDir().getAbsolutePath(),
            EmptyPieceStorageFactory.INSTANCE);
    waitDownloadComplete(torrentManager, 60);
  }

  private String createMultipleSeedersWithDifferentPieces(File baseFile, int piecesCount, int pieceSize, int numSeeders,
                                                          List<CommunicationManager> communicationManagerList, List<File> targetFiles) throws IOException, InterruptedException, URISyntaxException {

    List<byte[]> piecesList = new ArrayList<byte[]>(piecesCount);
    FileInputStream fin = new FileInputStream(baseFile);
    for (int i = 0; i < piecesCount; i++) {
      byte[] piece = new byte[pieceSize];
      fin.read(piece);
      piecesList.add(piece);
    }
    fin.close();

    final long torrentFileLength = baseFile.length();
    TorrentMetadata torrent = TorrentCreator.create(baseFile, null, this.tracker.getAnnounceURI(), null, "Test", pieceSize);
    File torrentFile = new File(baseFile.getParentFile(), baseFile.getName() + ".torrent");
    saveTorrent(torrent, torrentFile);


    for (int i = 0; i < numSeeders; i++) {
      final File baseDir = tempFiles.createTempDir();
      targetFiles.add(new File(baseDir, baseFile.getName()));
      final File seederPiecesFile = new File(baseDir, baseFile.getName());
      RandomAccessFile raf = new RandomAccessFile(seederPiecesFile, "rw");
      raf.setLength(torrentFileLength);
      for (int pieceIdx = i; pieceIdx < piecesCount; pieceIdx += numSeeders) {
        raf.seek(pieceIdx * pieceSize);
        raf.write(piecesList.get(pieceIdx));
      }
      CommunicationManager communicationManager = createClient(" communicationManager idx " + i);
      communicationManagerList.add(communicationManager);
      communicationManager.addTorrent(torrentFile.getAbsolutePath(), baseDir.getAbsolutePath());
      communicationManager.start(InetAddress.getLocalHost());
    }
    return torrent.getHexInfoHash();
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
    for (CommunicationManager communicationManager : communicationManagerList) {
      communicationManager.stop();
    }
    stopTracker();
    tempFiles.cleanup();
  }

  private void startTracker() throws IOException {
    int port = 6969;
    this.tracker = new Tracker(port, "http://" + InetAddress.getLocalHost().getHostAddress() + ":" + port + "" + ANNOUNCE_URL);
    tracker.setAnnounceInterval(5);
    this.tracker.start(true);
  }

  private CommunicationManager createAndStartClient() throws IOException, InterruptedException {
    CommunicationManager communicationManager = createClient();
    communicationManager.start(InetAddress.getLocalHost());
    return communicationManager;
  }

  private CommunicationManager createClient(String name) {
    final CommunicationManager communicationManager = communicationManagerFactory.getClient(name);
    communicationManagerList.add(communicationManager);
    return communicationManager;
  }

  private CommunicationManager createClient() {
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
