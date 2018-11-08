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
import java.util.List;

public class SingleThreadHashesCalculator implements PiecesHashesCalculator {

  @Override
  public HashingResult calculateHashes(List<DataSourceHolder> sources, int pieceSize) throws IOException {
    final List<byte[]> hashes = new ArrayList<byte[]>();
    List<Long> sourcesSizes = CommonHashingCalculator.INSTANCE.processDataSources(
            sources,
            hashes,
            pieceSize,
            new CommonHashingCalculator.Processor() {
              @Override
              public void process(byte[] buffer) {
                byte[] hash = TorrentUtils.calculateSha1Hash(buffer);
                hashes.add(hash);
              }
            }
    );

    return new HashingResult(hashes, sourcesSizes);
  }
}
