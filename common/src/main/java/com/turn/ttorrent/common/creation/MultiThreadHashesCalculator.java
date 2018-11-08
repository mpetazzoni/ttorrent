/*
 * Copyright 2000-2018 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.turn.ttorrent.common.creation;

import com.turn.ttorrent.common.TorrentUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class MultiThreadHashesCalculator implements PiecesHashesCalculator {

  private final ExecutorService executor;
  private final int maxInMemoryPieces;

  public MultiThreadHashesCalculator(ExecutorService executor, int maxInMemoryPieces) {
    this.executor = executor;
    this.maxInMemoryPieces = maxInMemoryPieces;
  }

  @Override
  public HashingResult calculateHashes(List<DataSourceHolder> sources, int pieceSize) throws IOException {
    final List<byte[]> hashes = new ArrayList<byte[]>();
    final List<Future<byte[]>> futures = new ArrayList<Future<byte[]>>();
    List<Long> sourcesSizes = CommonHashingCalculator.INSTANCE.processDataSources(
            sources,
            hashes,
            pieceSize,
            new CommonHashingCalculator.Processor() {
              @Override
              public void process(final byte[] buffer) {
                awaitHashesCalculationAndStore(futures, hashes, maxInMemoryPieces);
                final byte[] bufferCopy = Arrays.copyOf(buffer, buffer.length);
                futures.add(executor.submit(new Callable<byte[]>() {
                  @Override
                  public byte[] call() {
                    return TorrentUtils.calculateSha1Hash(bufferCopy);
                  }
                }));
              }
            }
    );
    awaitHashesCalculationAndStore(futures, hashes, 0);

    return new HashingResult(hashes, sourcesSizes);
  }

  private void awaitHashesCalculationAndStore(List<Future<byte[]>> futures, List<byte[]> hashes, int count) {
    while (futures.size() > count) {
      byte[] hash;
      try {
        Future<byte[]> future = futures.remove(0);
        hash = future.get();
      } catch (InterruptedException e) {
        throw new RuntimeException(e);
      } catch (ExecutionException e) {
        throw new RuntimeException(e);
      }
      hashes.add(hash);
    }
  }
}
