package com.turn.ttorrent.common;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class TorrentLoggerFactory {

  @Nullable
  private static volatile String staticLoggersName = null;

  public static Logger getLogger(Class<?> clazz) {
    String name = staticLoggersName;
    if (name == null) {
      name = clazz.getName();
    }
    return LoggerFactory.getLogger(name);
  }

  public static void setStaticLoggersName(@Nullable String staticLoggersName) {
    TorrentLoggerFactory.staticLoggersName = staticLoggersName;
  }
}
