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

import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

class Source {
  private final String path;
  private final DataSourceHolder sourceHolder;

  Source(File source) {
    this(source, source.getName());
  }

  Source(final File source, String path) {
    this.path = path;
    this.sourceHolder = new DataSourceHolder() {

      @Nullable
      private FileInputStream fis;

      @Override
      public InputStream getStream() throws IOException {
        if (fis == null) {
          fis = new FileInputStream(source);
        }
        return fis;
      }

      @Override
      public void close() throws IOException {
        if (fis != null) {
          fis.close();
        }
      }

      @Override
      public String toString() {
        return "Data source for file stream " + fis;
      }
    };
  }

  Source(final InputStream source, String path, final boolean closeAfterBuild) {
    this.path = path;
    this.sourceHolder = new DataSourceHolder() {
      @Override
      public InputStream getStream() {
        return source;
      }

      @Override
      public void close() throws IOException {
        if (closeAfterBuild) {
          source.close();
        }
      }

      @Override
      public String toString() {
        return "Data source for user's stream " + source;
      }
    };
  }

  String getPath() {
    return path;
  }

  DataSourceHolder getSourceHolder() {
    return sourceHolder;
  }
}
