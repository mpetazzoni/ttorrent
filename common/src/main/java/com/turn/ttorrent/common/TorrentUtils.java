package com.turn.ttorrent.common;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;

public final class TorrentUtils {


  /**
   * @param data for hashing
   * @return sha 1 hash of specified data
   * @throws NoSuchAlgorithmException if sha 1 algorithm is not available
   */
  public static byte[] calculateSha1Hash(byte[] data) throws NoSuchAlgorithmException {
    MessageDigest md = MessageDigest.getInstance("SHA-1");
    md.update(data);
    return md.digest();
  }

  /**
   * Convert a byte string to a string containing an hexadecimal
   * representation of the original data.
   *
   * @param bytes The byte array to convert.
   */
  public static String byteArrayToHexString(byte[] bytes) {
    BigInteger bi = new BigInteger(1, bytes);
    return String.format("%0" + (bytes.length << 1) + "X", bi);
  }

  public static List<String> getTorrentFileNames(TorrentMultiFileMetadata metadata) {
    List<String> result = new ArrayList<String>();

    for (TorrentFile torrentFile : metadata.getFiles()) {
      result.add(torrentFile.getRelativePathAsString());
    }

    return result;
  }

}
