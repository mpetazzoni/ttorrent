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
public interface ErrorListener {

    // TODO: Use this for all protocol violations, stats, etc.
    public void error(@Nonnull String message);
}
