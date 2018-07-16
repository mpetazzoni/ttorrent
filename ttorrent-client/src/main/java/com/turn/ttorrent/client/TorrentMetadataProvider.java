package com.turn.ttorrent.client;

import com.turn.ttorrent.bcodec.InvalidBEncodingException;
import com.turn.ttorrent.common.TorrentMetadata;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;

public interface TorrentMetadataProvider {

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
