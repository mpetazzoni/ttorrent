package com.turn.ttorrent;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.common.LoggerUtils;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ClientFactory {

  public final static int DEFAULT_POOL_SIZE = 10;

  public Client getClient(String name) {
    final ExecutorService executorService = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    final ExecutorService pieceValidatorExecutor = Executors.newFixedThreadPool(DEFAULT_POOL_SIZE);
    return new Client(executorService, pieceValidatorExecutor) {
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
