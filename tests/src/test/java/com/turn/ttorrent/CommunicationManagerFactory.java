package com.turn.ttorrent;

import com.turn.ttorrent.client.CommunicationManager;
import com.turn.ttorrent.common.LoggerUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public class CommunicationManagerFactory {

  public final static int DEFAULT_POOL_SIZE = 10;

  public CommunicationManager getClient(String name) {
    final ExecutorService executorService = new ThreadPoolExecutor(
            DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(4000));
    final ExecutorService pieceValidatorExecutor = new ThreadPoolExecutor(
            DEFAULT_POOL_SIZE, DEFAULT_POOL_SIZE,
            0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<Runnable>(400));
    return new CommunicationManager(executorService, pieceValidatorExecutor) {
      @Override
      public void stop() {
        super.stop();

        int timeout = 60;
        TimeUnit timeUnit = TimeUnit.SECONDS;

        executorService.shutdown();
        pieceValidatorExecutor.shutdown();
        if (timeout > 0) {
          try {
            if (!pieceValidatorExecutor.awaitTermination(timeout, timeUnit)) {
              logger.warn("unable to terminate executor service in {} {}", timeout, timeUnit);
            }
            boolean shutdownCorrectly = executorService.awaitTermination(timeout, timeUnit);
            if (!shutdownCorrectly) {
              logger.warn("unable to terminate executor service in {} {}", timeout, timeUnit);
            }
          } catch (InterruptedException e) {
            LoggerUtils.warnAndDebugDetails(logger, "unable to await termination executor service, thread was interrupted", e);
          }
        }

      }
    };
  }
}
