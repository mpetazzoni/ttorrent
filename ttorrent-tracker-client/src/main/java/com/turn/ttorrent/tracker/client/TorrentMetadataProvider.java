/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.tracker.client;

import java.net.URI;
import java.util.List;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;

/**
 *
 * @author shevek
 */
public interface TorrentMetadataProvider {

    public enum State {

        WAITING,
        VALIDATING,
        SHARING,
        SEEDING,
        ERROR,
        DONE;
    };

    @Nonnull
    public byte[] getInfoHash();

    @Nonnull
    public State getState();

    @Nonnull
    public List<? extends List<? extends URI>> getAnnounceList();

    @Nonnegative
    public long getUploaded();

    @Nonnegative
    public long getDownloaded();

    @Nonnegative
    public long getLeft();
}
