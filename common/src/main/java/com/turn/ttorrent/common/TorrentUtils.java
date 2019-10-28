package com.turn.ttorrent.common;

import org.apache.commons.codec.digest.DigestUtils;

import java.util.ArrayList;
import java.util.List;

public final class TorrentUtils {

  private final static char[] HEX_SYMBOLS = "0123456789ABCDEF".toCharArray();

  /**
   * @param data for hashing
   * @return sha 1 hash of specified data
   */
  public static byte[] calculateSha1Hash(byte[] data) {
    return DigestUtils.sha1(data);
  }

  /**
   * Convert a byte string to a string containing an hexadecimal
   * representation of the original data.
   *
   * @param bytes The byte array to convert.
   */
  public static String byteArrayToHexString(byte[] bytes) {
    char[] hexChars = new char[bytes.length * 2];
    for (int j = 0; j < bytes.length; j++) {
      int v = bytes[j] & 0xFF;
      hexChars[j * 2] = HEX_SYMBOLS[v >>> 4];
      hexChars[j * 2 + 1] = HEX_SYMBOLS[v & 0x0F];
    }
    return new String(hexChars);
  }

  public static boolean isTrackerLessInfo(AnnounceableInformation information) {
    return information.getAnnounce() == null && information.getAnnounceList() == null;
  }

  public static List<String> getTorrentFileNames(TorrentMetadata metadata) {
    List<String> result = new ArrayList<String>();

    for (TorrentFile torrentFile : metadata.getFiles()) {
      result.add(torrentFile.getRelativePathAsString());
    }

    return result;
  }

}
