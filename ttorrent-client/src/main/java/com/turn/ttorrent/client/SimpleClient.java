package com.turn.ttorrent.client;

import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class SimpleClient extends Client {

  private final static int DEFAULT_EXECUTOR_SIZE = 10;

  public SimpleClient() {
    this(DEFAULT_EXECUTOR_SIZE, DEFAULT_EXECUTOR_SIZE);
  }

  public SimpleClient(int workingExecutorSize, int validatorExecutorSize) {
    super(Executors.newFixedThreadPool(workingExecutorSize), Executors.newFixedThreadPool(validatorExecutorSize));
  }

  @Override
  public void stop() {
    super.stop();
  }

  @Override
  void stop(int timeout, TimeUnit timeUnit) {
    super.stop(timeout, timeUnit);
    Exception interruptedException = null;
    boolean anyFailedByTimeout = false;
    for (ExecutorService executorService : Arrays.asList(getExecutor(), getPieceValidatorExecutor())) {
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


}
