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
import java.util.List;

public interface PiecesHashesCalculator {

  /**
   * calculates sha1 hashes of each chunk with specified piece size
   * and returns list of hashes and stream's sizes. If one stream is ended and piece size threshold is not reached
   * implementation must read bytes from next stream
   * For example if source list is 3 streams with next bytes:
   * first stream: [1,2,3]
   * second stream: [4,5,6,7]
   * third stream: [8,9]
   * and pieceSize = 4
   * result must contain source size [3,4,2] and hashes: [sha1(1,2,3,4), sha1(5,6,7,8), sha1(9)]
   *
   * @param sources   list of input stream's providers
   * @param pieceSize size of one piece
   * @return see above
   * @throws IOException if IO error occurs in reading from streams
   */
  HashingResult calculateHashes(List<DataSourceHolder> sources, int pieceSize) throws IOException;

}
