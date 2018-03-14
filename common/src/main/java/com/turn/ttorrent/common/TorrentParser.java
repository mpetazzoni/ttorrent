package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BDecoder;
import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;
import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.*;

import static com.turn.ttorrent.common.TorrentMetadataKeys.*;

public class TorrentParser {

  private final static int PIECE_HASH_LENGTH = 20;

  public TorrentMultiFileMetadata parseFromFile(File torrentFile) throws IOException, NoSuchAlgorithmException {
    final FileInputStream fileInputStream = new FileInputStream(torrentFile);
    try {
      byte[] fileContent = new byte[(int) torrentFile.length()];
      fileInputStream.read(fileContent);
      return parse(fileContent);
    } finally {
      fileInputStream.close();
    }
  }

  /**
   * @param metadata binary .torrent content
   * @return parsed metadata object. This parser also wraps single torrent as multi torrent with one file
   * @throws InvalidBEncodingException if metadata has incorrect BEP format or missing required fields
   * @throws NoSuchAlgorithmException  if the SHA-1 algorithm is not available.
   * @throws RuntimeException          It's wrapped io exception from bep decoder.
   *                                   This exception doesn't must to throw io exception because reading from
   *                                   byte array input stream cannot throw the exception
   */
  public TorrentMultiFileMetadata parse(byte[] metadata) throws InvalidBEncodingException, NoSuchAlgorithmException, RuntimeException {
    final Map<String, BEValue> dictionaryMetadata;
    try {
      dictionaryMetadata = BDecoder.bdecode(new ByteArrayInputStream(metadata)).getMap();
    } catch (InvalidBEncodingException e) {
      throw e;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    final Map<String, BEValue> infoTable = getRequiredValueOrThrowException(dictionaryMetadata, INFO_TABLE).getMap();

    final BEValue creationDateValue = dictionaryMetadata.get(CREATION_DATE_SEC);
    final long creationDate = creationDateValue == null ? -1 : creationDateValue.getLong();

    final String comment = getStringOrNull(dictionaryMetadata, COMMENT);
    final String createdBy = getStringOrNull(dictionaryMetadata, CREATED_BY);
    final String announceUrl = getRequiredValueOrThrowException(dictionaryMetadata, ANNOUNCE).getString();
    final List<List<String>> trackers = getTrackers(dictionaryMetadata);
    final int pieceLength = getRequiredValueOrThrowException(infoTable, PIECE_LENGTH).getInt();
    final byte[] piecesHashes = getRequiredValueOrThrowException(infoTable, PIECES).getBytes();

    final boolean torrentContainsManyFiles = infoTable.get(FILES) != null;

    final String dirName = getRequiredValueOrThrowException(infoTable, NAME).getString();

    final List<TorrentFile> files = parseFiles(infoTable, torrentContainsManyFiles, dirName);

    if (piecesHashes.length % PIECE_HASH_LENGTH != 0)
      throw new InvalidBEncodingException("Incorrect size of pieces hashes");

    final int piecesCount = piecesHashes.length / PIECE_HASH_LENGTH;

    byte[] infoTableBytes;
    try {
      infoTableBytes = BEncoder.bencode(infoTable).array();
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    return new TorrentMetadata(
            TorrentUtils.calculateSha1Hash(infoTableBytes),
            trackers,
            announceUrl,
            creationDate,
            comment,
            createdBy,
            dirName,
            files,
            piecesCount,
            pieceLength,
            piecesHashes
    );
  }

  private List<TorrentFile> parseFiles(Map<String, BEValue> infoTable, boolean torrentContainsManyFiles, String name) throws InvalidBEncodingException {
    if (!torrentContainsManyFiles) {
      final BEValue md5Sum = infoTable.get(MD5_SUM);
      return Collections.singletonList(new TorrentFile(
              Collections.singletonList(name),
              getRequiredValueOrThrowException(infoTable, FILE_LENGTH).getLong(),
              md5Sum == null ? null : md5Sum.getString()
      ));
    }

    List<TorrentFile> result = new ArrayList<TorrentFile>();
    for (BEValue file : infoTable.get(FILES).getList()) {
      Map<String, BEValue> fileInfo = file.getMap();
      List<String> path = new ArrayList<String>();
      path.add(name);
      for (BEValue pathElement : fileInfo.get(FILE_PATH).getList()) {
        path.add(pathElement.getString());
      }
      final BEValue md5Sum = infoTable.get(MD5_SUM);
      result.add(new TorrentFile(
              path,
              fileInfo.get(FILE_LENGTH).getLong(),
              md5Sum == null ? null : md5Sum.getString()));
    }
    return result;
  }

  @Nullable
  private String getStringOrNull(Map<String, BEValue> dictionaryMetadata, String key) throws InvalidBEncodingException {
    final BEValue value = dictionaryMetadata.get(key);
    if (value == null) return null;
    return value.getString();
  }

  @Nullable
  private List<List<String>> getTrackers(Map<String, BEValue> dictionaryMetadata) throws InvalidBEncodingException {
    final BEValue announceListValue = dictionaryMetadata.get(ANNOUNCE_LIST);
    if (announceListValue == null) return null;
    List<BEValue> announceList = announceListValue.getList();
    List<List<String>> result = new ArrayList<List<String>>();
    Set<String> allTrackers = new HashSet<String>();
    for (BEValue tv : announceList) {
      List<BEValue> trackers = tv.getList();
      if (trackers.isEmpty()) {
        continue;
      }

      List<String> tier = new ArrayList<String>();
      for (BEValue tracker : trackers) {
        final String url = tracker.getString();
        if (!allTrackers.contains(url)) {
          tier.add(url);
          allTrackers.add(url);
        }
      }

      if (!tier.isEmpty()) {
        result.add(tier);
      }
    }
    return result;
  }

  @NotNull
  private BEValue getRequiredValueOrThrowException(Map<String, BEValue> map, String key) throws InvalidBEncodingException {
    final BEValue value = map.get(key);
    if (value == null)
      throw new InvalidBEncodingException("Invalid metadata format. Map doesn't contain required field " + key);
    return value;
  }
}
