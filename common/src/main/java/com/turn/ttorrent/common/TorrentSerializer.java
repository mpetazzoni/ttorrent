package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.BEValue;
import com.turn.ttorrent.bcodec.BEncoder;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.turn.ttorrent.common.TorrentMetadataKeys.*;

public class TorrentSerializer {

  public byte[] serialize(TorrentMultiFileMetadata metadata) throws IOException {
    Map<String, BEValue> mapMetadata = new HashMap<String, BEValue>();
    Map<String, BEValue> infoTable = new HashMap<String, BEValue>();

    mapMetadata.put(ANNOUNCE, new BEValue(metadata.getAnnounce()));
    putOptionalIfPresent(mapMetadata, COMMENT, metadata.getComment());
    putOptionalIfPresent(mapMetadata, CREATED_BY, metadata.getCreatedBy());

    if (metadata.getCreationDate().isPresent())
      mapMetadata.put(CREATION_DATE_SEC, new BEValue(metadata.getCreationDate().get()));

    List<BEValue> beValueList = getAnnounceListAsBEValues(metadata.getAnnounceList());
    mapMetadata.put(ANNOUNCE_LIST, new BEValue(beValueList));
    infoTable.put(PIECE_LENGTH, new BEValue(metadata.getPieceLength()));
    infoTable.put(PIECES, new BEValue(metadata.getPiecesHashes()));
    infoTable.put(PRIVATE, new BEValue(metadata.isPrivate() ? 1 : 0));

    infoTable.put(NAME, new BEValue(metadata.getDirectoryName()));
    if (metadata.getFiles().size() == 1) {
      final TorrentFile torrentFile = metadata.getFiles().get(0);
      infoTable.put(FILE_LENGTH, new BEValue(torrentFile.size));
      putOptionalIfPresent(infoTable, MD5_SUM, torrentFile.md5Hash);
    } else {
      Map<String, BEValue> files = new HashMap<String, BEValue>();
      for (TorrentFile torrentFile : metadata.getFiles()) {
        files.put(FILE_LENGTH, new BEValue(torrentFile.size));
        putOptionalIfPresent(files, MD5_SUM, torrentFile.md5Hash);
        files.put(FILE_PATH, new BEValue(mapStringListToBEValueList(torrentFile.relativePath)));
      }
      infoTable.put(FILES, new BEValue(files));
    }

    mapMetadata.put(INFO_TABLE, new BEValue(infoTable));

    final ByteBuffer buffer = BEncoder.bencode(mapMetadata);
    return buffer.array();
  }

  private List<BEValue> getAnnounceListAsBEValues(List<List<String>> announceList) throws UnsupportedEncodingException {
    List<BEValue> result = new ArrayList<BEValue>();

    for (List<String> announceTier : announceList) {
      List<BEValue> tier = mapStringListToBEValueList(announceTier);
      result.add(new BEValue(tier));
    }

    return result;
  }

  private List<BEValue> mapStringListToBEValueList(List<String> list) throws UnsupportedEncodingException {
    List<BEValue> result = new ArrayList<BEValue>();
    for (String s : list) {
      result.add(new BEValue(s));
    }
    return result;
  }

  private void putOptionalIfPresent(Map<String, BEValue> map, String key, Optional<String> optional) throws UnsupportedEncodingException {
    if (!optional.isPresent()) return;
    map.put(key, new BEValue(optional.get()));
  }

}
