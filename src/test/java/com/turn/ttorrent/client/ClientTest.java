package com.turn.ttorrent.client;

import com.turn.ttorrent.ClientFactory;
import com.turn.ttorrent.TempFiles;
import com.turn.ttorrent.Utils;
import com.turn.ttorrent.WaitFor;
import com.turn.ttorrent.client.peer.SharingPeer;
import com.turn.ttorrent.common.PeerUID;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.tracker.TrackedPeer;
import com.turn.ttorrent.tracker.TrackedTorrent;
import com.turn.ttorrent.tracker.Tracker;
import org.apache.commons.io.FileUtils;
import org.apache.log4j.*;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
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
 *         Date: 7/26/13
 *         Time: 2:32 PM
 */
@Test(timeOut = 600000)
public class ClientTest {

  private ClientFactory clientFactory;

  private List<Client> clientList;
  private static final String TEST_RESOURCES = "src/test/resources";
  private Tracker tracker;
  private TempFiles tempFiles;

  public ClientTest(){
    clientFactory = new ClientFactory();
    if (Logger.getRootLogger().getAllAppenders().hasMoreElements())
      return;
    BasicConfigurator.configure(new ConsoleAppender(new PatternLayout("[%d{MMdd HH:mm:ss,SSS} %t] %6p - %20.20c - %m %n")));
    Torrent.setHashingThreadsCount(1);
  }

  @BeforeMethod
  public void setUp() throws IOException {
    tempFiles = new TempFiles();
    clientList = new ArrayList<Client>();
    Logger.getRootLogger().setLevel(Utils.getLogLevel());
    startTracker();
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
      leech = createClient("leecher");
      leech.start(new InetAddress[]{InetAddress.getLocalHost()}, 5, null);
      for (File f : filesToShare) {
        File torrentFile = new File(f.getParentFile(), f.getName() + ".torrent");
        SharedTorrent st2 = SharedTorrent.fromFile(torrentFile, downloadDir, true);
        leech.addTorrent(st2);
      }

      new WaitFor(60 * 1000) {
        @Override
        protected boolean condition() {

          final Set<String> strings = listFileNames(downloadDir);
          int count = 0;
          final List<String> partItems = new ArrayList<String>();
          for (String s : strings) {
            if (s.endsWith(".part")){
              count++;
              partItems.add(s);
            }
          }
          if (count < 5){

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
      seeder.stop();
      leech.stop();
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
      assertEquals(torrents.size(), 1);
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

    assertTrue(srcFile.delete());

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
      final WaitFor waitFor = new WaitFor(60 * 1000) {
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

  public void corrupted_seeder()  throws NoSuchAlgorithmException, IOException, URISyntaxException, InterruptedException {
    this.tracker.setAcceptForeignTorrents(true);

    final int pieceSize = 48*1024; // lower piece size to reduce disk usage
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

      final Torrent torrent = Torrent.create(baseFile, null, this.tracker.getAnnounceURI(), null,  "Test", pieceSize);
      client2.addTorrent(new SharedTorrent(torrent, client2Dir, false, true));

      final String baseMD5 = getFileMD5(baseFile, md5);

      final Client leech = createAndStartClient();
      final File leechDestDir = tempFiles.createTempDir();
      final AtomicReference<Exception> thrownException = new AtomicReference<Exception>();
      final Thread th = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            leech.downloadUninterruptibly(new SharedTorrent(torrent, leechDestDir, false), 7);
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
          return  th.getState() == Thread.State.TERMINATED;
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
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.start(InetAddress.getLocalHost());

    downloadAndStop(torrent, 15*1000, createClient());
    Thread.sleep(2 * 1000);
    assertTrue(dwnlFile.exists() && dwnlFile.isFile());
    final boolean delete = dwnlFile.delete();
    assertTrue(delete && !dwnlFile.exists());
  }

  public void download_many_times() throws InterruptedException, NoSuchAlgorithmException, IOException {
    Client seeder = createClient();
    tracker.setAcceptForeignTorrents(true);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 7);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.start(InetAddress.getLocalHost());

    for(int i=0; i<5; i++) {
      downloadAndStop(torrent, 250*1000, createClient());
      Thread.sleep(3*1000);
    }
  }

  public void testConnectToAllDiscoveredPeers() throws Exception {
    tracker.setAcceptForeignTorrents(true);

    final ExecutorService executorService = Executors.newFixedThreadPool(8);
    Client leecher = new Client(executorService);
    leecher.setMaxInConnectionsCount(10);
    leecher.setMaxOutConnectionsCount(10);

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 34);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");
    final SharedTorrent sharedTorrent = new SharedTorrent(torrent, tempFiles.createTempDir(), true);

    final String hexInfoHash = sharedTorrent.getHexInfoHash();
    leecher.addTorrent(sharedTorrent);
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
          future.get(5, TimeUnit.SECONDS);
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

  public void download_io_error() throws InterruptedException, NoSuchAlgorithmException, IOException{
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 34);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    seeder.start(InetAddress.getLocalHost());

      final AtomicInteger interrupts = new AtomicInteger(0);
      final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
      final Client leech = new Client(es){
        @Override
        public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
          super.handlePieceCompleted(peer, piece);
          if (piece.getIndex()%4==0 && interrupts.incrementAndGet() <= 2){
            peer.unbind(true);
          }
        }

        @Override
        public void stop(int timeout, TimeUnit timeUnit) {
          super.stop(timeout, timeUnit);
          es.shutdown();
        }
      };
      //manually add leech here for graceful shutdown.
      clientList.add(leech);
      downloadAndStop(torrent, 45 * 1000, leech);
      Thread.sleep(2*1000);
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
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final Client leecher = new Client(es){
      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);
        es.shutdown();
      }

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
    final File destDir = tempFiles.createTempDir();
    final SharedTorrent st = new SharedTorrent(torrent, destDir, true);
    try {
      leecher.downloadUninterruptibly(st, 5);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (IOException ex){
      assertEquals(st.getClientState(),ClientState.DONE);
      // ensure .part was deleted:
      assertEquals(0, destDir.list().length);
    }

  }

  public void download_uninterruptibly_timeout() throws InterruptedException, NoSuchAlgorithmException, IOException  {
    tracker.setAcceptForeignTorrents(true);
    Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 24);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    final AtomicInteger piecesDownloaded = new AtomicInteger(0);
    final ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    Client leecher = new Client(es){
      @Override
      public void handlePieceCompleted(SharingPeer peer, Piece piece) throws IOException {
        piecesDownloaded.incrementAndGet();
        try {
          Thread.sleep(piecesDownloaded.get()*500);
        } catch (InterruptedException e) {

        }
      }

      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);
        es.shutdown();
      }
    };
    clientList.add(leecher);
    leecher.start(InetAddress.getLocalHost());
    final SharedTorrent st = new SharedTorrent(torrent, tempFiles.createTempDir(), true);
    try {
      leecher.downloadUninterruptibly(st, 5);
      fail("Must fail, because file wasn't downloaded completely");
    } catch (IOException ex){
      assertEquals(st.getClientState(),ClientState.DONE);
    }
  }

  public void canStartAndStopClientTwice() throws Exception {
    ExecutorService es = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final Client client = new Client(es);
    clientList.add(client);
    try {
      client.start(InetAddress.getLocalHost());
      client.stop();
      client.start(InetAddress.getLocalHost());
      client.stop();
    } finally {
      es.shutdown();
    }
  }

  public void peer_dies_during_download() throws InterruptedException, NoSuchAlgorithmException, IOException {
    tracker.setAnnounceInterval(5);
    final Client seed1 = createClient();
    final Client seed2 = createClient();

    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 240);
    final Torrent torrent = Torrent.create(dwnlFile, tracker.getAnnounceURI(), "Test");

    seed1.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true, true));
    seed1.start(InetAddress.getLocalHost());
    seed1.setAnnounceInterval(5);
    seed2.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true, true));
    seed2.start(InetAddress.getLocalHost());
    seed2.setAnnounceInterval(5);

    Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    leecher.setAnnounceInterval(5);
    final SharedTorrent st = new SharedTorrent(torrent, tempFiles.createTempDir(), true);
    final ExecutorService service = Executors.newFixedThreadPool(1);
    final Future<?> future = service.submit(new Runnable() {
      @Override
      public void run() {
        try {
          Thread.sleep(5 * 1000);
          seed1.removeTorrent(torrent);
          Thread.sleep(3*1000);
          seed1.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true, true));
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
      leecher.downloadUninterruptibly(st, 60);
    } finally {
      future.cancel(true);
      service.shutdown();
    }
  }

  public void interrupt_download() throws IOException, InterruptedException, NoSuchAlgorithmException {
    tracker.setAcceptForeignTorrents(true);
    final Client seeder = createClient();
    final File dwnlFile = tempFiles.createTempFile(513 * 1024 * 60);
    final Torrent torrent = Torrent.create(dwnlFile, null, tracker.getAnnounceURI(), "Test");

    seeder.start(InetAddress.getLocalHost());
    seeder.addTorrent(new SharedTorrent(torrent, dwnlFile.getParentFile(), true));
    final Client leecher = createClient();
    leecher.start(InetAddress.getLocalHost());
    final SharedTorrent st = new SharedTorrent(torrent, tempFiles.createTempDir(), true);
    final AtomicBoolean interrupted = new AtomicBoolean();
    final Thread th = new Thread(){
      @Override
      public void run() {
        try {
          leecher.downloadUninterruptibly(st, 30);
        } catch (IOException e) {
          e.printStackTrace();
        } catch (InterruptedException e) {
          interrupted.set(true);
          return;
        }
      }
    };
    th.start();
    Thread.sleep(10);
    th.interrupt();
    new WaitFor(10*1000){
      @Override
      protected boolean condition() {
        return !th.isAlive();
      }
    };

    assertTrue(st.getClientState() != ClientState.SEEDING);
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

    for (int i=0; i<40; i++) {
      byte[] data = new byte[20];
      random.nextBytes(data);
      announce.addPeer(new TrackedPeer(tt, "my_unknown_and_unreachablehost" + i, 6881, ByteBuffer.wrap(data)));
    }
    seeder.addTorrent(completeTorrent("file1.jar.torrent"));
    final SharedTorrent incompleteTorrent = incompleteTorrent("file1.jar.torrent", leechFolder);
    leecher.addTorrent(incompleteTorrent);
    new WaitFor(10*1000){

      @Override
      protected boolean condition() {
        return incompleteTorrent.isComplete();
      }
    };
  }

  public void test_seeding_does_not_change_file_modification_date() throws IOException, InterruptedException, NoSuchAlgorithmException {
    File srcFile = tempFiles.createTempFile(1024);
    long time = srcFile.lastModified();

    Thread.sleep(1000);

    Client seeder = createAndStartClient();

    final Torrent torrent = Torrent.create(srcFile, null, tracker.getAnnounceURI(), "Test");

    final SharedTorrent sharedTorrent = new SharedTorrent(torrent, srcFile.getParentFile(), true, true);
    seeder.addTorrent(sharedTorrent);

    assertEquals(time, srcFile.lastModified());
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
      Client client = createClient(" client idx " + i);
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
