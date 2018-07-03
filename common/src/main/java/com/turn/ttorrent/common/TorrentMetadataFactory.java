package com.turn.ttorrent.common;

import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface TorrentMetadataFactory {

  /**
   * load and return new {@link TorrentMetadata} instance from any source
   *
   * @return new torrent metadata instance
   * @throws IOException               if any IO error occurs
   * @throws InvalidBEncodingException if specified source has invalid BEP format or missed required fields
   */
  @NotNull
  TorrentMetadata getTorrentMetadata() throws IOException;

}
