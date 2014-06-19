/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client;

import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface ClientListener {

    public void clientStateChanged(@Nonnull Client client, @Nonnull Client.State state);

    public void torrentStateChanged(@Nonnull Client client, @Nonnull TorrentHandler torrent, @Nonnull TorrentHandler.State state);
}
