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

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class CommonHashingCalculator {

  static final CommonHashingCalculator INSTANCE = new CommonHashingCalculator();

  public List<Long> processDataSources(List<DataSourceHolder> sources,
                                       List<byte[]> hashes,
                                       int pieceSize,
                                       Processor processor) throws IOException {
    List<Long> sourcesSizes = new ArrayList<Long>();
    byte[] buffer = new byte[pieceSize];
    int read = 0;
    for (DataSourceHolder source : sources) {
      long streamSize = 0;
      InputStream stream = source.getStream();
      try {
        while (true) {
          int readFromStream = stream.read(buffer, read, buffer.length - read);
          if (readFromStream <= 0) {
            break;
          }
          streamSize += readFromStream;
          read += readFromStream;

          if (read != buffer.length) {
            break;
          }
          processor.process(buffer);
          read = 0;
        }
      } finally {
        source.close();
      }
      sourcesSizes.add(streamSize);
    }
    if (read > 0) {
      processor.process(Arrays.copyOf(buffer, read));
    }

    return sourcesSizes;
  }

  interface Processor {

    /**
     * Invoked when next piece is received from data source. Array will be overwritten
     * after invocation this method (next piece will be read in same array). So multi-threading
     * implementations must create copy of array and work with the copy.
     *
     * @param buffer byte array which contains bytes from data sources.
     *               length of array equals piece size excluding last piece
     */
    void process(byte[] buffer);

  }
}
