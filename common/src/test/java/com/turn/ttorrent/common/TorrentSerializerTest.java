package com.turn.ttorrent.common;

import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

@Test
public class TorrentSerializerTest {

  private TorrentSerializer myTorrentSerializer;

  @BeforeMethod
  public void setUp() {
    myTorrentSerializer = new TorrentSerializer();
  }

  public void testWebSeeds() throws IOException {
    List<String> webSeeds = new ArrayList<String>();
    webSeeds.add("http://localhost/webseed1");
    webSeeds.add("ftp://localhost/webseed2");
    webSeeds.add("https://localhost/ABC");

    final List<TorrentFile> files = new ArrayList<TorrentFile>();
    final List<String> relativePath = new ArrayList<String>();
    relativePath.add("file.txt");
    files.add(new TorrentFile(relativePath, 10, null));
    final TorrentMetadataImpl metadata =
            new TorrentMetadataImpl(new byte[0], null, "main-announce", 19,
                    "comment", "created by", "name", files, 10,
                    10, new byte[10], webSeeds
            );

    final byte[] output = myTorrentSerializer.serialize(metadata);
    assertNotNull(output);

    final String outputString = new String(output);
    System.out.println(outputString);

    for (String url : webSeeds) {
      assertTrue(
              outputString.contains(url.length() + ":" + url),
              "url not found in output: " + url + ", output: " + outputString
      );
    }
  }

}
