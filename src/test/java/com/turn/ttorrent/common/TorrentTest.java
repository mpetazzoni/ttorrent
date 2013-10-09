package com.turn.ttorrent.common;

import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
import org.apache.commons.io.FileUtils;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.*;
import static org.testng.Assert.assertEqualsNoOrder;

@Test
public class TorrentTest {

  public void test_create_torrent() throws URISyntaxException, IOException, NoSuchAlgorithmException, InterruptedException {
    URI announceURI = new URI("http://localhost:6969/announce");
    String createdBy = "Test";
    Torrent t = Torrent.create(new File("src/test/resources/parentFiles/file1.jar"), announceURI, createdBy);
    assertEquals(createdBy, t.getCreatedBy());
    assertEquals(announceURI, t.getAnnounceList().get(0).get(0));
  }

  public void load_torrent_created_by_utorrent() throws IOException, NoSuchAlgorithmException, URISyntaxException {
    Torrent t = Torrent.load(new File("src/test/resources/torrents/file1.jar.torrent"));
    assertEquals(new URI("http://localhost:6969/announce"), t.getAnnounceList().get(0).get(0));
    assertEquals("B92D38046C76D73948E14C42DF992CAF25489D08", t.getHexInfoHash());
    assertEquals("uTorrent/3130", t.getCreatedBy());
  }

  public void torrent_from_multiple_files() throws URISyntaxException, InterruptedException, NoSuchAlgorithmException, IOException {
    URI announceURI = new URI("http://localhost:6969/announce");
    String createdBy = "Test2";
    final File parentDir = new File("src/test/resources/parentFiles/parentDir");
    final long creationTimeSecs = 1376051000;
    Torrent t = Torrent.create(parentDir,addFilesRecursively(parentDir), announceURI, null, createdBy, creationTimeSecs, Torrent.DEFAULT_PIECE_LENGTH);
    File torrentFileWin = new File("src/test/resources/torrents/parentDir.win.torrent");
    File torrentFileLinux = new File("src/test/resources/torrents/parentDir.linux.torrent");
    final byte[] expectedBytesWin = FileUtils.readFileToByteArray(torrentFileWin);
    final byte[] expectedBytesLinux = FileUtils.readFileToByteArray(torrentFileLinux);
    final byte[] actualBytes = t.getEncoded();

    assertTrue(HexBin.encode(expectedBytesWin).equals(HexBin.encode(actualBytes)) || HexBin.encode(expectedBytesLinux).equals(HexBin.encode(actualBytes)));
  }

  public void testFilenames() throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File("src/test/resources/torrents/parentDir.win.torrent");
    Torrent t2 = Torrent.load(torrentFile);
    final List<String> tmpFileNames = t2.getFilenames();
    final List<String> normalizedFilenames = new ArrayList<String>(tmpFileNames.size());
    for (String filename : tmpFileNames) {
      normalizedFilenames.add(filename.replaceAll("\\\\", "/"));
    }
    String[] expectedFilenames = new String[]
    {"parentDir/AccuRevCommon.jar",
     "parentDir/commons-io-cio2.5_3.jar",
     "parentDir/commons-io-cio2.5_3.jar.link",
     "parentDir/inDir/application.wadl",
     "parentDir/storage.version"};
    assertEqualsNoOrder(normalizedFilenames.toArray(new String[normalizedFilenames.size()]), expectedFilenames);
    System.out.println();
  }

  private static List<File> addFilesRecursively(File baseDir){
    final File[] files = baseDir.listFiles();
    if (files == null) {
      return Collections.emptyList();
    }
    List<File> list = new ArrayList<File>();
    for (File file : files) {
      if (file.isDirectory()){
        list.addAll(addFilesRecursively(file));
      } else if (file.isFile()){
        list.add(file);
      }
    }
    return list;
  }
}
