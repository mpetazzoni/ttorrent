package com.turn.ttorrent.client;

public interface PieceInformation {

  /**
   * @return piece index. Indexing starts from zero
   */
  int getIndex();

  /**
   * @return piece size. This value must be equals piece size specified by metadata excluding last piece
   */
  int getSize();

}
