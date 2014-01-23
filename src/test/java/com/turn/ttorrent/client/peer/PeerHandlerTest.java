/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.peer;

import com.turn.ttorrent.client.Client;
import com.turn.ttorrent.client.io.PeerClientHandshakeHandler;
import com.turn.ttorrent.client.io.PeerServerHandshakeHandler;
import com.turn.ttorrent.test.TestPeerPieceProvider;
import com.turn.ttorrent.common.Peer;
import com.turn.ttorrent.common.Torrent;
import com.turn.ttorrent.test.TorrentTestUtils;
import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.local.LocalAddress;
import io.netty.channel.local.LocalChannel;
import io.netty.channel.local.LocalEventLoopGroup;
import io.netty.channel.local.LocalServerChannel;
import java.io.File;
import java.net.InetSocketAddress;
import java.util.Arrays;
import org.easymock.EasyMock;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author shevek
 */
public class PeerHandlerTest {

    private static final Logger LOG = LoggerFactory.getLogger(PeerHandlerTest.class);

    @Test
    public void testPeerHandler() throws Exception {
        byte[] peerId = Arrays.copyOf(new byte[]{1, 2, 3, 4, 5, 6}, 20);
        InetSocketAddress peerAddress = new InetSocketAddress(1234);
        File dir = TorrentTestUtils.newTorrentDir("PeerHandlerTest-server");
        Torrent torrent = TorrentTestUtils.newTorrent(dir, 12345, true);
        TestPeerPieceProvider provider = new TestPeerPieceProvider(torrent);
        PeerActivityListener activityListener = EasyMock.createMock(PeerActivityListener.class);
        PeerHandler peerHandler = new PeerHandler(new Peer(peerAddress, peerId), provider, activityListener);

        PeerConnectionListener connectionListener = EasyMock.createMock(PeerConnectionListener.class);

        EasyMock.reset(activityListener, connectionListener);
        EasyMock.replay(activityListener, connectionListener);
        peerHandler.run();
        EasyMock.verify(activityListener, connectionListener);

        LocalAddress address = new LocalAddress("test");
        LocalEventLoopGroup group = new LocalEventLoopGroup();
        SERVER:
        {
            Client client = new Client(torrent, dir);
            ServerBootstrap b = new ServerBootstrap()
                    .group(group)
                    .channel(LocalServerChannel.class)
                    .childHandler(new PeerServerHandshakeHandler(client));
            b.bind(address).sync();
        }

        Channel channel;
        CLIENT:
        {
            Bootstrap b = new Bootstrap()
                    .group(group)
                    .channel(LocalChannel.class)
                    .handler(new PeerClientHandshakeHandler(peerId, peerHandler, connectionListener));
            channel = b.connect(address).sync().channel();
        }

        EasyMock.reset(activityListener, connectionListener);
        EasyMock.replay(activityListener, connectionListener);
        peerHandler.setChannel(channel);
        peerHandler.run();
        EasyMock.verify(activityListener, connectionListener);

        if (true)
            return;

        EasyMock.reset(activityListener, connectionListener);
        EasyMock.replay(activityListener, connectionListener);
        provider.setPieceHandler(0);
        peerHandler.run();
        EasyMock.verify(activityListener, connectionListener);

        EasyMock.reset(activityListener, connectionListener);
        EasyMock.replay(activityListener, connectionListener);
        peerHandler.run();
        EasyMock.verify(activityListener, connectionListener);

    }
}