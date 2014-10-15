/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.storage;

import javax.annotation.Nonnegative;

/**
 *
 * @author shevek
 */
public interface ByteRangeStorage extends ByteStorage {

    @Nonnegative
    public long offset();

    @Nonnegative
    public long size();
}
