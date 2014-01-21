/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.google.common.reflect.AbstractInvocationHandler;
import com.turn.ttorrent.client.PeerPieceProvider;
import com.turn.ttorrent.client.Piece;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Arrays;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class SharingPeerTest {

    private static final Logger LOG = LoggerFactory.getLogger(SharingPeerTest.class);

    public static class DummyPeerPieceProvider implements PeerPieceProvider {

        private final Torrent torrent;

        public DummyPeerPieceProvider(Torrent torrent) {
            this.torrent = torrent;
        }

        @Override
        public byte[] getInfoHash() {
            return torrent.getInfoHash();
        }

        @Override
        public int getPieceCount() {
            return torrent.getPieceCount();
        }

        @Override
        public Piece getPiece(int index) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public DownloadingPiece getNextPieceToDownload(SharingPeer peer) {
            throw new UnsupportedOperationException("Not supported yet.");
        }

        @Override
        public void readBlock(ByteBuffer block, int piece, int offset) throws IOException {
            // Nothing required.
        }

        @Override
        public void writeBlock(ByteBuffer block, int piece, int offset) throws IOException {
            // Apparently consume the block.
            block.position(block.limit());
        }
    }

    public static class LoggingInvocationHandler extends AbstractInvocationHandler {

        @Override
        protected Object handleInvocation(Object proxy, Method method, Object[] args) throws Throwable {
            LOG.info(method + "(" + Arrays.toString(args) + ")");
            return null;
        }
    }

    @Test
    public void testSharingPeer() throws Exception {
        byte[] peerId = new byte[]{1, 2, 3, 4, 5, 6};
        InetSocketAddress address = new InetSocketAddress(1234);
        Torrent torrent = DownloadingPieceTest.newTorrent(12345, true);
        PeerPieceProvider provider = new DummyPeerPieceProvider(torrent);
        PeerActivityListener listener = (PeerActivityListener) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[]{PeerActivityListener.class},
                new LoggingInvocationHandler());
        SharingPeer peer = new SharingPeer(new Peer(address, peerId), provider, listener);

    }
}