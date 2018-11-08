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

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;

public interface DataSourceHolder extends Closeable {

  /**
   * provides {@link InputStream} associated with the holder. Holder can just store reference to stream or create
   * new stream from some source (e.g. {@link java.io.FileInputStream} from {@link java.io.File}) on first invocation.
   *
   * @return {@link InputStream} associated with the holder.
   * @throws IOException if io error occurs in creating new stream from source.
   *                     IO exception can be thrown only on first invocation
   */
  InputStream getStream() throws IOException;

}
