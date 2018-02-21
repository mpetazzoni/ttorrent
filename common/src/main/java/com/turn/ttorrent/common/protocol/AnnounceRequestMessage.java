package com.turn.ttorrent.common.protocol;

/**
 * Base interface for announce request messages.
 *
 * <p>
 * This interface must be implemented by all subtypes of announce request
 * messages for the various tracker protocols.
 * </p>
 *
 * @author mpetazzoni
 */
public interface AnnounceRequestMessage {

  int DEFAULT_NUM_WANT = 50;

  /**
   * Announce request event types.
   *
   * <p>
   * When the client starts exchanging on a torrent, it must contact the
   * torrent's tracker with a 'started' announce request, which notifies the
   * tracker this client now exchanges on this torrent (and thus allows the
   * tracker to report the existence of this peer to other clients).
   * </p>
   *
   * <p>
   * When the client stops exchanging, or when its download completes, it must
   * also send a specific announce request. Otherwise, the client must send an
   * eventless (NONE), periodic announce request to the tracker at an
   * interval specified by the tracker itself, allowing the tracker to
   * refresh this peer's status and acknowledge that it is still there.
   * </p>
   */
  enum RequestEvent {
    NONE(0),
    COMPLETED(1),
    STARTED(2),
    STOPPED(3);

    private final int id;

    RequestEvent(int id) {
      this.id = id;
    }

    public String getEventName() {
      return this.name().toLowerCase();
    }

    public int getId() {
      return this.id;
    }

    public static RequestEvent getByName(String name) {
      for (RequestEvent type : RequestEvent.values()) {
        if (type.name().equalsIgnoreCase(name)) {
          return type;
        }
      }
      return null;
    }

    public static RequestEvent getById(int id) {
      for (RequestEvent type : RequestEvent.values()) {
        if (type.getId() == id) {
          return type;
        }
      }
      return null;
    }
  }

  byte[] getInfoHash();

  String getHexInfoHash();

  byte[] getPeerId();

  String getHexPeerId();

  int getPort();

  long getUploaded();

  long getDownloaded();

  long getLeft();

  boolean getCompact();

  boolean getNoPeerIds();

  RequestEvent getEvent();

  String getIp();

  int getNumWant();
}
