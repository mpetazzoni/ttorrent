package com.turn.ttorrent.common;

/**
 * Abstract time service. Provides current time millis.
 */
public interface TimeService {
  /**
   * Provides current time millis.
   *
   * @return current time.
   */
  long now();

}
