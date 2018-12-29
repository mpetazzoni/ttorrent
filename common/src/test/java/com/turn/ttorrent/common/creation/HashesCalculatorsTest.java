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
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static java.util.Arrays.asList;
import static org.testng.Assert.assertEquals;

@Test
public class HashesCalculatorsTest {

  private List<? extends PiecesHashesCalculator> implementations;
  private ExecutorService executor;

  @BeforeMethod
  public void setUp() {
    executor = Executors.newFixedThreadPool(4);
    implementations = Arrays.asList(
            new SingleThreadHashesCalculator(),
            new MultiThreadHashesCalculator(executor, 3),
            new MultiThreadHashesCalculator(executor, 20),
            new MultiThreadHashesCalculator(executor, 1)
    );
  }

  @AfterMethod
  public void tearDown() throws InterruptedException {
    executor.shutdown();
    executor.awaitTermination(10, TimeUnit.SECONDS);
  }

  public void testEmptySource() throws IOException {
    List<byte[]> sourceBytes = new ArrayList<byte[]>();
    sourceBytes.add(new byte[]{1, 2});
    sourceBytes.add(new byte[]{});
    sourceBytes.add(new byte[]{3, 4});

    HashingResult expected = new HashingResult(asList(
            TorrentUtils.calculateSha1Hash(new byte[]{1, 2, 3, 4})),
            asList(2L, 0L, 2L)
    );
    verifyImplementationsResults(sourceBytes, 512, expected);
  }

  public void testStreamsAsPiece() throws IOException {
    List<byte[]> sourceBytes = new ArrayList<byte[]>();
    sourceBytes.add(new byte[]{1, 2, 3, 4});
    sourceBytes.add(new byte[]{5, 6, 7, 8});

    HashingResult expected = new HashingResult(asList(
            TorrentUtils.calculateSha1Hash(new byte[]{1, 2, 3, 4}),
            TorrentUtils.calculateSha1Hash(new byte[]{5, 6, 7, 8})),
            asList(4L, 4L)
    );
    verifyImplementationsResults(sourceBytes, 4, expected);
  }

  public void testWithSmallSource() throws IOException {
    List<byte[]> sourceBytes = new ArrayList<byte[]>();
    sourceBytes.add(new byte[]{0, 1, 2, 3, 4, 5, 4});
    sourceBytes.add(new byte[]{-1, -2});
    sourceBytes.add(new byte[]{6, 7, 8, 9, 10});
    sourceBytes.add(new byte[]{1, 2, 3, 4});

    HashingResult expected = new HashingResult(asList(
            TorrentUtils.calculateSha1Hash(new byte[]{0, 1, 2, 3, 4, 5}),
            TorrentUtils.calculateSha1Hash(new byte[]{4, -1, -2, 6, 7, 8}),
            TorrentUtils.calculateSha1Hash(new byte[]{9, 10, 1, 2, 3, 4})),
            asList(7L, 2L, 5L, 4L)
    );
    verifyImplementationsResults(sourceBytes, 6, expected);
  }

  public void testOneLargeSource() throws IOException {

    int size = 1024 * 1024 * 100;//100mb
    byte[] sourceBytes = new byte[size];
    List<byte[]> hashes = new ArrayList<byte[]>();
    final int pieceSize = 128 * 1024;//128kb
    for (int i = 0; i < sourceBytes.length; i++) {
      sourceBytes[i] = (byte) (i * i);
      if (i % pieceSize == 0 && i > 0) {
        byte[] forHashing = Arrays.copyOfRange(sourceBytes, i - pieceSize, i);
        hashes.add(TorrentUtils.calculateSha1Hash(forHashing));
      }
    }
    hashes.add(TorrentUtils.calculateSha1Hash(
            Arrays.copyOfRange(sourceBytes, hashes.size() * pieceSize, size)
    ));

    HashingResult expected = new HashingResult(hashes, Collections.singletonList((long) size));

    verifyImplementationsResults(Collections.singletonList(sourceBytes), pieceSize, expected);
  }

  private void verifyImplementationsResults(List<byte[]> sourceBytes,
                                            int pieceSize,
                                            HashingResult expected) throws IOException {
    List<HashingResult> hashingResults = new ArrayList<HashingResult>();
    for (PiecesHashesCalculator implementation : implementations) {
      List<DataSourceHolder> sources = new ArrayList<DataSourceHolder>();
      for (byte[] sourceByte : sourceBytes) {
        addSource(sourceByte, sources);
      }
      hashingResults.add(implementation.calculateHashes(sources, pieceSize));
    }
    for (HashingResult actual : hashingResults) {
      assertHashingResult(actual, expected);
    }
  }

  private void assertHashingResult(HashingResult actual, HashingResult expected) {

    assertEquals(actual.getHashes().size(), expected.getHashes().size());
    for (int i = 0; i < actual.getHashes().size(); i++) {
      assertEquals(actual.getHashes().get(i), expected.getHashes().get(i));
    }
    assertEquals(actual.getSourceSizes(), expected.getSourceSizes());
  }

  private void addSource(byte[] bytes, List<DataSourceHolder> sources) {
    final ByteArrayInputStream stream = new ByteArrayInputStream(bytes);
    sources.add(new DataSourceHolder() {
      @Override
      public InputStream getStream() {
        return stream;
      }

      @Override
      public void close() {
      }
    });
  }
}
