package com.turn.ttorrent.client;

import java.io.IOException;
import java.net.InetAddress;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

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
    TorrentManager torrentManager = startDownloading(torrentFile, downloadDir, iPv4Address);
    final Semaphore semaphore = new Semaphore(0);
    torrentManager.addListener(new TorrentListenerWrapper() {
      @Override
      public void downloadComplete() {
        semaphore.release();
      }
    });
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
}
