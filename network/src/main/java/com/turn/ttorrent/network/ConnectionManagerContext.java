package com.turn.ttorrent.network;

import java.util.concurrent.ExecutorService;

public interface ConnectionManagerContext extends ChannelListenerFactory {

  ExecutorService getExecutor();

}
