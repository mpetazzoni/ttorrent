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

/**
 * @author Sergey.Pak
 *         Date: 9/19/13
 *         Time: 2:57 PM
 */
public class TorrentDefaults {
  public static final int ANNOUNCE_INTERVAL_SEC=60;
  public static final int FILESIZE_THRESHOLD_MB=10;

  public final static int SOCKET_CONNECTION_TIMEOUT_MILLIS = 100000;
  public static final int SELECTOR_SELECT_TIMEOUT = 10000;
  public static final int CLEANUP_RUN_TIMEOUT = 120000;


}
