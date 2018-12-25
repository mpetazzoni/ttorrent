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

import com.turn.ttorrent.Constants;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.common.TimeService;
import org.mockito.Mockito;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import static com.turn.ttorrent.common.TorrentMetadataKeys.*;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNull;

@Test
public class MetadataBuilderTest {

  public void testMultiFileModeWithOneFile() throws IOException {
    Map<String, BEValue> map = new MetadataBuilder()
            .setDirectoryName("root")
            .addDataSource(new ByteArrayInputStream(new byte[]{1, 2}), "path/some_file", true)
            .buildBEP().getMap();
    Map<String, BEValue> info = map.get(INFO_TABLE).getMap();
    assertEquals(info.get(NAME).getString(), "root");
    List<BEValue> files = info.get(FILES).getList();
    assertEquals(files.size(), 1);
    Map<String, BEValue> file = files.get(0).getMap();
    assertEquals(file.get(FILE_LENGTH).getInt(), 2);

    StringBuilder path = new StringBuilder();
    Iterator<BEValue> iterator = file.get(FILE_PATH).getList().iterator();
    if (iterator.hasNext()) {
      path = new StringBuilder(iterator.next().getString());
    }
    while (iterator.hasNext()) {
      path.append("/").append(iterator.next().getString());
    }
    assertEquals(path.toString(), "path/some_file");
  }

  public void testSingleFile() throws IOException {

    TimeService timeService = Mockito.mock(TimeService.class);
    long now = System.currentTimeMillis();
    Mockito.when(timeService.now()).thenReturn(now);

    byte[] data = {1, 2, 12, 4, 5};
    Map<String, BEValue> metadata = new MetadataBuilder(timeService)
            .addDataSource(new ByteArrayInputStream(data), "singleFile.txt", true)
            .setTracker("http://localhost:12346")
            .buildBEP().getMap();
    assertEquals(metadata.get(ANNOUNCE).getString(), "http://localhost:12346");
    assertNull(metadata.get(CREATION_DATE_SEC));
    assertNull(metadata.get(COMMENT));
    assertEquals(metadata.get(CREATED_BY).getString(), "ttorrent library");

    Map<String, BEValue> info = metadata.get(INFO_TABLE).getMap();
    assertEquals(info.get(PIECES).getBytes().length / Constants.PIECE_HASH_SIZE, 1);
    assertEquals(info.get(PIECE_LENGTH).getInt(), 512 * 1024);

    assertEquals(info.get(FILE_LENGTH).getInt(), data.length);
    assertEquals(info.get(NAME).getString(), "singleFile.txt");

  }

  public void testMultiFileWithOneFileValues() throws IOException {

    TimeService timeService = Mockito.mock(TimeService.class);
    long now = System.currentTimeMillis();
    Mockito.when(timeService.now()).thenReturn(now);

    byte[] data = {34, 2, 12, 4, 5};
    List<String> paths = Arrays.asList("unix/path", "win\\path");
    Map<String, BEValue> metadata = new MetadataBuilder(timeService)
            .addDataSource(new ByteArrayInputStream(data), paths.get(0), true)
            .addDataSource(new ByteArrayInputStream(data), paths.get(1), true)
            .setDirectoryName("downloadDirName")
            .buildBEP().getMap();

    Map<String, BEValue> info = metadata.get(INFO_TABLE).getMap();
    assertEquals(info.get(PIECES).getBytes().length, Constants.PIECE_HASH_SIZE);
    assertEquals(info.get(PIECE_LENGTH).getInt(), 512 * 1024);
    assertEquals(info.get(NAME).getString(), "downloadDirName");

    int idx = 0;
    for (BEValue value : info.get(FILES).getList()) {
      Map<String, BEValue> fileInfo = value.getMap();
      String path = paths.get(idx);
      idx++;
      String[] split = path.split("[/\\\\]");
      List<BEValue> list = fileInfo.get(FILE_PATH).getList();

      assertEquals(fileInfo.get(FILE_LENGTH).getInt(), data.length);
      assertEquals(list.size(), split.length);

      for (int i = 0; i < list.size(); i++) {
        assertEquals(list.get(i).getString(), split[i]);
      }
    }

  }
}
