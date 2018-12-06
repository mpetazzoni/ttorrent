package com.turn.ttorrent.client;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class EventDispatcher {

  private final List<TorrentListener> listeners;
  private final TorrentListener notifyer;

  public EventDispatcher() {
    this.listeners = new CopyOnWriteArrayList<TorrentListener>();
    this.notifyer = createNotifyer();
  }

  private TorrentListener createNotifyer() {
    return new TorrentListener() {
      @Override
      public void peerConnected(PeerInformation peerInformation) {
        for (TorrentListener listener : listeners) {
          listener.peerConnected(peerInformation);
        }
      }

      @Override
      public void peerDisconnected(PeerInformation peerInformation) {
        for (TorrentListener listener : listeners) {
          listener.peerDisconnected(peerInformation);
        }
      }

      @Override
      public void pieceDownloaded(PieceInformation pieceInformation, PeerInformation peerInformation) {
        for (TorrentListener listener : listeners) {
          listener.pieceDownloaded(pieceInformation, peerInformation);
        }
      }

      @Override
      public void downloadComplete() {
        for (TorrentListener listener : listeners) {
          listener.downloadComplete();
        }
      }

      @Override
      public void pieceReceived(PieceInformation pieceInformation, PeerInformation peerInformation) {
        for (TorrentListener listener : listeners) {
          listener.pieceReceived(pieceInformation, peerInformation);
        }
      }

      @Override
      public void downloadFailed(Throwable cause) {
        for (TorrentListener listener : listeners) {
          listener.downloadFailed(cause);
        }
      }

      @Override
      public void validationComplete(int validpieces, int totalpieces) {
        for (TorrentListener listener : listeners) {
          listener.validationComplete(validpieces, totalpieces);
        }
      }
    };
  }

  TorrentListener multicaster() {
    return notifyer;
  }

  public boolean removeListener(TorrentListener listener) {
    return listeners.remove(listener);
  }

  public void addListener(TorrentListener listener) {
    listeners.add(listener);
  }
}
