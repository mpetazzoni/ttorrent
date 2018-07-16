/*
 * Copyright 2000-2013 JetBrains s.r.o.
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

package com.turn.ttorrent;

import java.nio.ByteBuffer;

/**
 * @author Sergey.Pak
 * Date: 9/19/13
 * Time: 2:57 PM
 */
public class Constants {
  public static final int DEFAULT_ANNOUNCE_INTERVAL_SEC = 15;

  public final static int DEFAULT_SOCKET_CONNECTION_TIMEOUT_MILLIS = 100000;
  public static final int DEFAULT_CONNECTION_TIMEOUT_MILLIS = 10000;

  public static final int DEFAULT_MAX_CONNECTION_COUNT = 100;

  public static final ByteBuffer EMPTY_BUFFER = ByteBuffer.allocate(0);

  public static final int DEFAULT_SELECTOR_SELECT_TIMEOUT_MILLIS = 10000;
  public static final int DEFAULT_CLEANUP_RUN_TIMEOUT_MILLIS = 120000;

  public static final String BYTE_ENCODING = "ISO-8859-1";

  public static final int PIECE_HASH_SIZE = 20;

}
