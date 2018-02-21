package com.turn.ttorrent.tracker;

import com.turn.ttorrent.MockTimeService;
import com.turn.ttorrent.common.protocol.AnnounceRequestMessage;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.testng.Assert.*;

@Test
public class TorrentsRepositoryTest {

  private TorrentsRepository myTorrentsRepository;

  @BeforeMethod
  public void setUp() throws Exception {
    myTorrentsRepository = new TorrentsRepository(10);
  }

  @AfterMethod
  public void tearDown() throws Exception {

  }

  public void testThatTorrentsStoredInRepository() {
    assertEquals(myTorrentsRepository.getTorrents().size(), 0);
    final TrackedTorrent torrent = new TrackedTorrent(new byte[]{1, 2, 3});

    myTorrentsRepository.putIfAbsent(torrent.getHexInfoHash(), torrent);
    assertTrue(myTorrentsRepository.getTorrent(torrent.getHexInfoHash()) == torrent);
    final TrackedTorrent torrentCopy = new TrackedTorrent(new byte[]{1, 2, 3});

    myTorrentsRepository.putIfAbsent(torrentCopy.getHexInfoHash(), torrentCopy);
    assertTrue(myTorrentsRepository.getTorrent(torrent.getHexInfoHash()) == torrent);
    assertEquals(myTorrentsRepository.getTorrents().size(), 1);

    final TrackedTorrent secondTorrent = new TrackedTorrent(new byte[]{3, 2, 1});
    myTorrentsRepository.putIfAbsent(secondTorrent.getHexInfoHash(), secondTorrent);
    assertEquals(myTorrentsRepository.getTorrents().size(), 2);
  }

  public void testPutIfAbsentAndUpdate() throws UnsupportedEncodingException {

    final AtomicBoolean updateInvoked = new AtomicBoolean();
    TrackedTorrent torrent = new TrackedTorrent(new byte[]{1, 2, 3}) {
      @Override
      public TrackedPeer update(AnnounceRequestMessage.RequestEvent event, ByteBuffer peerId, String hexPeerId, String ip, int port, long uploaded, long downloaded, long left) throws UnsupportedEncodingException {
        updateInvoked.set(true);
        return super.update(event, peerId, hexPeerId, ip, port, uploaded, downloaded, left);
      }
    };
    myTorrentsRepository.putIfAbsentAndUpdate(torrent.getHexInfoHash(), torrent,
            AnnounceRequestMessage.RequestEvent.STARTED, ByteBuffer.allocate(5), "0",
            "127.0.0.1", 6881, 5, 10, 12);
    assertTrue(updateInvoked.get());
    assertEquals(torrent.getPeers().size(), 1);
    final TrackedPeer trackedPeer = torrent.getPeers().values().iterator().next();
    assertEquals(trackedPeer.getIp(), "127.0.0.1");
    assertEquals(trackedPeer.getPort(), 6881);
    assertEquals(trackedPeer.getLeft(), 12);
    assertEquals(trackedPeer.getDownloaded(), 10);
    assertEquals(trackedPeer.getUploaded(), 5);
  }

  public void testThatCleanupDontLockAllTorrentsAndStorage() throws UnsupportedEncodingException {

    final Semaphore cleanFinishLock = new Semaphore(0);
    final Semaphore cleanStartLock = new Semaphore(0);
    final TrackedTorrent torrent = new TrackedTorrent(new byte[]{1, 2, 3}) {
      @Override
      public void collectUnfreshPeers(int expireTimeoutSec) {
        cleanStartLock.release();
        try {
          if (!cleanFinishLock.tryAcquire(1, TimeUnit.SECONDS)) {
            fail("can not acquire semaphore");
          }
        } catch (InterruptedException e) {
          fail("can not finish cleanup", e);
        }
      }
    };

    myTorrentsRepository.putIfAbsent(torrent.getHexInfoHash(), torrent);
    torrent.addPeer(new TrackedPeer(torrent, "127.0.0.1", 6881, ByteBuffer.allocate(10)));
    assertEquals(myTorrentsRepository.getTorrents().size(), 1);

    final ExecutorService executorService = Executors.newSingleThreadExecutor();

    try {
      final Future<Integer> cleanupFuture = executorService.submit(new Callable<Integer>() {
        @Override
        public Integer call() throws Exception {
          myTorrentsRepository.cleanup(1);
          return 0;
        }
      });
      try {
        if (!cleanStartLock.tryAcquire(1, TimeUnit.SECONDS)) {
          fail("cannot acquire semaphore");
        }
      } catch (InterruptedException e) {
        fail("don't received that cleanup is started", e);
      }

      final TrackedTorrent secondTorrent = new TrackedTorrent(new byte[]{3, 1, 1});

      myTorrentsRepository.putIfAbsentAndUpdate(secondTorrent.getHexInfoHash(), secondTorrent,
              AnnounceRequestMessage.RequestEvent.STARTED, ByteBuffer.allocate(5), "0",
              "127.0.0.1", 6881, 0, 0, 1);

      cleanFinishLock.release();
      try {
        cleanupFuture.get(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        fail("cleanup was interrupted", e);
      } catch (ExecutionException e) {
        fail("cleanup was failed with execution exception", e);
      } catch (TimeoutException e) {
        fail("cannot get result from future", e);
      }
    } finally {
      executorService.shutdown();
    }
  }

  public void testThatTorrentsCanRemovedFromStorage() throws UnsupportedEncodingException {
    TrackedTorrent torrent = new TrackedTorrent(new byte[]{1, 2, 3});

    MockTimeService timeService = new MockTimeService();
    timeService.setTime(10000);
    final TrackedPeer peer = new TrackedPeer(torrent, "127.0.0.1", 6881, ByteBuffer.allocate(5), timeService);
    torrent.addPeer(peer);

    timeService.setTime(15000);
    final TrackedPeer secondPeer = new TrackedPeer(torrent, "127.0.0.1", 6882, ByteBuffer.allocate(5), timeService);
    torrent.addPeer(secondPeer);

    myTorrentsRepository.putIfAbsent(torrent.getHexInfoHash(), torrent);

    assertEquals(myTorrentsRepository.getTorrents().size(), 1);
    assertEquals(torrent.getPeers().size(), 2);

    timeService.setTime(17000);
    myTorrentsRepository.cleanup(10);

    assertEquals(myTorrentsRepository.getTorrents().size(), 1);
    assertEquals(torrent.getPeers().size(), 2);

    timeService.setTime(23000);
    myTorrentsRepository.cleanup(10);

    assertEquals(myTorrentsRepository.getTorrents().size(), 1);
    assertEquals(torrent.getPeers().size(), 1);

    timeService.setTime(40000);
    myTorrentsRepository.cleanup(10);

    assertEquals(myTorrentsRepository.getTorrents().size(), 0);
    assertEquals(torrent.getPeers().size(), 0);

  }
}
