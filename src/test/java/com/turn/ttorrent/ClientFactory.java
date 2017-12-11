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
    return new Client(name, executorService) {
      @Override
      public void stop(int timeout, TimeUnit timeUnit) {
        super.stop(timeout, timeUnit);

        executorService.shutdown();
        if (timeout > 0) {
          try {
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
