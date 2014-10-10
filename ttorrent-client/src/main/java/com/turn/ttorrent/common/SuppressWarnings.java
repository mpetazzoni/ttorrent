/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.common;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 *
 * @author shevek
 */
@Retention(RetentionPolicy.RUNTIME)
public @interface SuppressWarnings {

    public String value();
}
