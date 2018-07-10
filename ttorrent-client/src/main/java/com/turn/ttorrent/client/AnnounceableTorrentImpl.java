package com.turn.ttorrent.client;

import com.turn.ttorrent.client.storage.PieceStorage;
import com.turn.ttorrent.common.AnnounceableInformation;
import com.turn.ttorrent.common.TorrentHash;
import com.turn.ttorrent.common.TorrentStatistic;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;

public class AnnounceableTorrentImpl implements LoadedTorrent {

  private final TorrentStatistic torrentStatistic;
  private final TorrentHash torrentHash;
  private final List<List<String>> announceUrls;
  private final String announce;
  private final PieceStorage pieceStorage;
  private final String dotTorrentFilePath;
  private final EventDispatcher eventDispatcher;

  public AnnounceableTorrentImpl(TorrentStatistic torrentStatistic,
                                 final String hexInfoHash,
                                 final byte[] infoHash,
                                 @Nullable List<List<String>> announceUrls,
                                 String announce,
                                 PieceStorage pieceStorage,
                                 String dotTorrentFilePath,
                                 EventDispatcher eventDispatcher) {
    this.torrentStatistic = torrentStatistic;
    this.eventDispatcher = eventDispatcher;
    torrentHash = new TorrentHash() {
      @Override
      public byte[] getInfoHash() {
        return infoHash;
      }

      @Override
      public String getHexInfoHash() {
        return hexInfoHash;
      }
    };
    if (announceUrls != null) {
      this.announceUrls = Collections.unmodifiableList(announceUrls);
    } else {
      this.announceUrls = Collections.singletonList(Collections.singletonList(announce));
    }
    this.announce = announce;
    this.dotTorrentFilePath = dotTorrentFilePath;
    this.pieceStorage = pieceStorage;
  }

  @Override
  public PieceStorage getPieceStorage() {
    return pieceStorage;
  }

  @Override
  public String getDotTorrentFilePath() {
    return dotTorrentFilePath;
  }

  @Override
  public TorrentStatistic getTorrentStatistic() {
    return torrentStatistic;
  }

  @Override
  @NotNull
  public AnnounceableInformation createAnnounceableInformation() {
    return new AnnounceableInformationImpl(
            torrentStatistic.getUploadedBytes(),
            torrentStatistic.getDownloadedBytes(),
            torrentStatistic.getLeftBytes(),
            torrentHash,
            announceUrls,
            announce
    );
  }

  @Override
  public TorrentHash getTorrentHash() {
    return torrentHash;
  }

  @Override
  public EventDispatcher getEventDispatcher() {
    return eventDispatcher;
  }

  @Override
  public String toString() {
    return "AnnounceableTorrentImpl{" +
            "piece storage='" + pieceStorage + '\'' +
            ", dot torrent file='" + dotTorrentFilePath + '\'' +
            '}';
  }
}
