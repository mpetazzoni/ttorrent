package com.turn.ttorrent.stubs;

import com.sun.org.apache.xerces.internal.impl.dv.util.HexBin;
import com.turn.ttorrent.common.TorrentInfo;

/**
 * @author Sergey.Pak
 *         Date: 8/9/13
 *         Time: 6:04 PM
 */
public class SimpleTorrentInfo implements TorrentInfo {

  private final long myUploaded;
  private final long myDownloaded;
  private final long myLeft;
  private final byte[] myHashBytes;
  private final String myHashString;

  public SimpleTorrentInfo(String hashString){
    this(0,0,0, HexBin.decode(hashString), hashString);
  }

  public SimpleTorrentInfo(byte[] hashBytes){
    this(0,0,0, hashBytes, HexBin.encode(hashBytes));
  }

  public SimpleTorrentInfo(long uploaded, long downloaded, long left, byte[] hashBytes, String hashString) {
    myUploaded = uploaded;
    myDownloaded = downloaded;
    myLeft = left;
    myHashBytes = hashBytes;
    myHashString = hashString;
  }

  @Override
  public long getUploaded() {
    return myUploaded;
  }

  @Override
  public long getDownloaded() {
    return myDownloaded;
  }

  @Override
  public long getLeft() {
    return myLeft;
  }

  @Override
  public int getPieceCount() {
    return 100000;
  }

  @Override
  public long getPieceSize(int pieceIdx) {
    return 2*1024*1024;
  }

  @Override
  public byte[] getInfoHash() {
    return myHashBytes;
  }

  @Override
  public String getHexInfoHash() {
    return myHashString;
  }
}
