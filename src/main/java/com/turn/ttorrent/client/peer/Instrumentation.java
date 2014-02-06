/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.io.PeerMessage;

/**
 * This is a hook class to interject cheeky behaviour into the client(s).
 *
 * @author shevek
 */
public class Instrumentation {

    public PeerMessage.RequestMessage instrumentBlockRequest(PeerHandler peer, PeerPieceProvider provider, PeerMessage.RequestMessage request) {
        return request;
    }
}
