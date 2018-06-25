package com.turn.ttorrent.common;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.io.FileUtils;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

import static org.testng.Assert.*;

@Test
public class TorrentTest {

  public void test_create_torrent() throws URISyntaxException, IOException, NoSuchAlgorithmException, InterruptedException {
    URI announceURI = new URI("http://localhost:6969/announce");
    String createdBy = "Test";
    TorrentMultiFileMetadata t = TorrentCreator.create(new File("src/test/resources/parentFiles/file1.jar"), announceURI, createdBy);
    assertEquals(createdBy, t.getCreatedBy().get());
    assertEquals(announceURI.toString(), t.getAnnounce());
  }

  public void load_torrent_created_by_utorrent() throws IOException, NoSuchAlgorithmException, URISyntaxException {
    TorrentMultiFileMetadata t = new TorrentParser().parseFromFile(new File("src/test/resources/torrents/file1.jar.torrent"));
    assertEquals("http://localhost:6969/announce", t.getAnnounce());
    assertEquals("B92D38046C76D73948E14C42DF992CAF25489D08", t.getHexInfoHash());
    assertEquals("uTorrent/3130", t.getCreatedBy().get());
  }

  public void torrent_from_multiple_files() throws URISyntaxException, InterruptedException, NoSuchAlgorithmException, IOException {
    URI announceURI = new URI("http://localhost:6969/announce");
    String createdBy = "Test2";
    final File parentDir = new File("src/test/resources/parentFiles/parentDir");
    final long creationTimeSecs = 1376051000;
    final String[] fileNames = new String[]
            {"AccuRevCommon.jar",
                    "commons-io-cio2.5_3.jar",
                    "commons-io-cio2.5_3.jar.link",
                    "inDir/application.wadl",
                    "storage.version"};
    final List<File> files = new ArrayList<File>();
    for (String fileName : fileNames) {
      files.add(new File(parentDir, fileName));
    }
    TorrentMultiFileMetadata createdTorrent = TorrentCreator.create(parentDir, files, announceURI, null, createdBy, creationTimeSecs, TorrentCreator.DEFAULT_PIECE_LENGTH);
    File torrentFileWin = new File("src/test/resources/torrents/parentDir.win.torrent");
    File torrentFileLinux = new File("src/test/resources/torrents/parentDir.linux.torrent");
    final byte[] expectedBytesWin = FileUtils.readFileToByteArray(torrentFileWin);
    final byte[] expectedBytesLinux = FileUtils.readFileToByteArray(torrentFileLinux);
    final byte[] actualBytes = new TorrentSerializer().serialize(createdTorrent);

    new TorrentParser().parse(actualBytes);

    assertTrue(Hex.encodeHexString(expectedBytesWin).equals(Hex.encodeHexString(actualBytes)) || Hex.encodeHexString(expectedBytesLinux).equals(Hex.encodeHexString(actualBytes)));
  }

  public void testFilenames() throws IOException, NoSuchAlgorithmException {
    File torrentFile = new File("src/test/resources/torrents/parentDir.win.torrent");
    TorrentMultiFileMetadata t2 = new TorrentParser().parseFromFile(torrentFile);
    final List<TorrentFile> tmpFileNames = t2.getFiles();
    final List<String> normalizedFilenames = new ArrayList<String>(tmpFileNames.size());
    for (TorrentFile torrentFileInfo : tmpFileNames) {
      normalizedFilenames.add(torrentFileInfo.getRelativePathAsString().replaceAll("\\\\", "/"));
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

}
