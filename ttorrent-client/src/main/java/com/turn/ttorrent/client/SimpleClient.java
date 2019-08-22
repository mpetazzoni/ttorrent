package com.turn.ttorrent.client;

import com.turn.ttorrent.common.TorrentMetadata;
import com.turn.ttorrent.common.TorrentStatistic;

import java.io.IOException;
import java.lang.reflect.Field;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class SimpleClient {

  private final static int DEFAULT_EXECUTOR_SIZE = 10;
  private final CommunicationManager communicationManager;

  public SimpleClient() {
    this(DEFAULT_EXECUTOR_SIZE, DEFAULT_EXECUTOR_SIZE);
  }

  public SimpleClient(int workingExecutorSize, int validatorExecutorSize) {
    communicationManager = new CommunicationManager(Executors.newFixedThreadPool(workingExecutorSize), Executors.newFixedThreadPool(validatorExecutorSize));
  }

  public void stop() {
    stop(60, TimeUnit.SECONDS);
  }

  public void stop(int timeout, TimeUnit timeUnit) {
    communicationManager.stop(timeout, timeUnit);
    Exception interruptedException = null;
    boolean anyFailedByTimeout = false;
    for (ExecutorService executorService : Arrays.asList(
            communicationManager.getExecutor(),
            communicationManager.getPieceValidatorExecutor())) {
      executorService.shutdown();

      //if the thread is already interrupted don't try to await termination
      if (Thread.currentThread().isInterrupted()) continue;

      try {
        if (!executorService.awaitTermination(timeout, timeUnit)) {
          anyFailedByTimeout = true;
        }
      } catch (InterruptedException e) {
        interruptedException = e;
      }
    }
    if (interruptedException != null) {
      throw new RuntimeException("Thread was interrupted, " +
              "shutdown methods are invoked but maybe tasks are not finished yet", interruptedException);
    }
    if (anyFailedByTimeout)
      throw new RuntimeException("At least one executor was not fully shutdown because timeout was elapsed");

  }

  public void downloadTorrent(String torrentFile, String downloadDir, InetAddress iPv4Address) throws IOException, InterruptedException {
    communicationManager.start(iPv4Address);
    final Semaphore semaphore = new Semaphore(0);
    List<TorrentListener> listeners = Collections.<TorrentListener>singletonList(
            new TorrentListenerWrapper() {

              @Override
              public void validationComplete(int validpieces, int totalpieces) {
                if (validpieces == totalpieces) semaphore.release();
              }

              @Override
              public void downloadComplete() {
                semaphore.release();
              }
            }
    );
    TorrentManager torrentManager = communicationManager.addTorrent(torrentFile, downloadDir, listeners);
    semaphore.acquire();
  }

  private TorrentManager startDownloading(String torrentFile, String downloadDir, InetAddress iPv4Address) throws IOException {
    communicationManager.start(iPv4Address);
    return communicationManager.addTorrent(torrentFile, downloadDir);
  }

  public TorrentManager downloadTorrentAsync(String torrentFile,
                                             String downloadDir,
                                             InetAddress iPv4Address) throws IOException {
    return startDownloading(torrentFile, downloadDir, iPv4Address);
  }


  /**
   * Get statistics for a given torrent file
   * @param dotTorrentFilePath
   * @return
   * @throws IOException If unable to get torrent metadata
   * @throws IllegalStateException If the torrent has not been loaded
   */
  public TorrentStatistic getStatistics(String dotTorrentFilePath) throws IOException {
    FileMetadataProvider metadataProvider = new FileMetadataProvider(dotTorrentFilePath);
    TorrentMetadata metadata = metadataProvider.getTorrentMetadata();
    LoadedTorrent loadedTorrent = communicationManager.getTorrentsStorage().getLoadedTorrent(metadata.getHexInfoHash());
    if (loadedTorrent != null) {
      return new TorrentStatistic(loadedTorrent.getTorrentStatistic());
    }

    throw new IllegalStateException("Torrent has not been loaded yet");

  }


  public boolean hasStop() {
    return communicationManager.hasStop();
  }
}
