/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package com.turn.ttorrent.client.io;

import com.google.common.base.Preconditions;
import com.google.common.primitives.Ints;
import com.google.common.primitives.Shorts;
import com.google.common.primitives.UnsignedBytes;
import com.turn.ttorrent.protocol.bcodec.BEUtils;
import com.turn.ttorrent.protocol.bcodec.BEValue;
import com.turn.ttorrent.protocol.bcodec.NettyBDecoder;
import com.turn.ttorrent.protocol.bcodec.NettyBEncoder;
import com.turn.ttorrent.client.peer.PeerHandler;
import io.netty.buffer.ByteBuf;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.CheckForNull;
import javax.annotation.CheckForSigned;
import javax.annotation.Nonnegative;
import javax.annotation.Nonnull;
import javax.annotation.OverridingMethodsMustInvokeSuper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supports extensions to the BitTorrent protocol with extended message types.
 *
 * Currently supported extensions:
 *
 * <ul>
 * <li>BEP 10 Extended Handshake: Allows peers to tell each other which protocol
 * extensions they support</li>
 * <li>UT Peer Exchange (UT_PEX): PEX allows peers to tell each other about
 * peers that they know about, lowering load on trackers and potentially
 * obviating them. There are two competing PEX protocols; we implement the one
 * that works within the BEP 10 extension protocol. Couldn't find a spec for it
 * anywhere, but a reference implementation is at
 * https://github.com/libtorrent/libtorrent/blob/master/src/ut_pex.cpp</li>
 * </ul>
 *
 * @author shevek
 */
public abstract class PeerExtendedMessage extends PeerMessage {

    private static final Logger LOG = LoggerFactory.getLogger(PeerExtendedMessage.class);

    public static enum ExtendedType {

        // Must be ordinal zero.
        handshake,
        ut_pex;
    }

    @Override
    public Type getType() {
        return Type.EXTENDED;
    }

    @Nonnull
    public abstract ExtendedType getExtendedType();

    @Override
    @OverridingMethodsMustInvokeSuper
    public void toWire(ByteBuf out, Map<? extends ExtendedType, ? extends Byte> extendedTypes) throws IOException {
        super.toWire(out, extendedTypes);
        Byte remoteType = extendedTypes.get(getExtendedType());
        if (remoteType == null)
            throw new NullPointerException("Unsupported extension " + getExtendedType() + "; remote supports " + extendedTypes);
        out.writeByte(remoteType);
    }

    public static class HandshakeMessage extends PeerExtendedMessage {

        public static final String K_MESSAGE_TYPES = "m";
        public static final String K_SENDER_IPV4 = "ipv4";
        public static final String K_SENDER_IPV6 = "ipv6";
        public static final String K_SENDER_PORT = "p";
        public static final String K_SENDER_VERSION = "v";
        public static final String K_SENDER_REQUEST_QUEUE_LENGTH = "reqq";
        public static final String K_RECEIVER_IP = "yourip";
        private final Map<ExtendedType, Byte> senderExtendedTypeMap = new EnumMap<ExtendedType, Byte>(ExtendedType.class);
        private byte[] senderIp4;
        private byte[] senderIp6;
        private int senderPort;
        private String senderVersion;
        private int senderRequestQueueLength;
        private byte[] receiverIp;

        /**
         * Constructed for remote, received at local.
         */
        public HandshakeMessage() {
            senderExtendedTypeMap.put(ExtendedType.handshake, (byte) 0);
        }

        /**
         * Constructed for local, transmitted to remote.
         */
        public HandshakeMessage(int senderRequestQueueLength, Set<? extends SocketAddress> senderAddresses) {
            this();
            this.senderRequestQueueLength = senderRequestQueueLength;
            for (ExtendedType type : ExtendedType.values())
                senderExtendedTypeMap.put(type, (byte) type.ordinal());
            for (SocketAddress senderAddress : senderAddresses) {
                if (!(senderAddress instanceof InetSocketAddress))
                    continue;
                InetSocketAddress socketAddress = (InetSocketAddress) senderAddress;
                InetAddress inetAddress = socketAddress.getAddress();
                if (inetAddress == null)
                    continue;
                if (inetAddress instanceof Inet4Address) {
                    senderIp4 = inetAddress.getAddress();
                    senderPort = socketAddress.getPort();
                } else if (inetAddress instanceof Inet6Address) {
                    senderIp6 = inetAddress.getAddress();
                    senderPort = socketAddress.getPort();
                }
            }
        }

        @Override
        public ExtendedType getExtendedType() {
            return ExtendedType.handshake;
        }

        @Nonnull
        public Map<ExtendedType, Byte> getSenderExtendedTypeMap() {
            return senderExtendedTypeMap;
        }

        @CheckForNull
        private InetAddress toAddress(@CheckForNull byte[] address) throws UnknownHostException {
            if (address == null)
                return null;
            return InetAddress.getByAddress(address);
        }

        @CheckForNull
        private InetSocketAddress toSocketAddress(@CheckForNull byte[] address, @CheckForSigned int defaultPort) throws UnknownHostException {
            if (address == null)
                return null;
            int port = senderPort;
            if (port <= 0)
                port = defaultPort;
            if (port <= 0)
                return null;
            InetAddress inetAddress = toAddress(address);
            if (inetAddress == null)
                return null;
            return new InetSocketAddress(inetAddress, port);
        }

        @CheckForNull
        public InetSocketAddress getSenderIp4Address(@CheckForSigned int defaultPort) throws UnknownHostException {
            return toSocketAddress(senderIp4, defaultPort);
        }

        @CheckForNull
        public InetSocketAddress getSenderIp6Address(@CheckForSigned int defaultPort) throws UnknownHostException {
            return toSocketAddress(senderIp6, defaultPort);
        }

        @CheckForNull
        public String getSenderVersion() {
            return senderVersion;
        }

        @CheckForSigned
        public int getSenderRequestQueueLength() {
            return senderRequestQueueLength;
        }

        @Override
        public void fromWire(ByteBuf in) throws IOException {
            NettyBDecoder decoder = new NettyBDecoder(in);
            Map<String, BEValue> payload = decoder.bdecodeMap().getMap();
            EXTMSG: {
                BEValue tmpValue = payload.get(K_MESSAGE_TYPES);
                if (tmpValue == null)
                    break EXTMSG;
                Map<String, BEValue> tmp = tmpValue.getMap();
                senderExtendedTypeMap.clear();
                for (Map.Entry<String, BEValue> e : tmp.entrySet()) {
                    try {
                        ExtendedType type = ExtendedType.valueOf(e.getKey());
                        senderExtendedTypeMap.put(type, UnsignedBytes.checkedCast(e.getValue().getInt()));
                    } catch (IllegalArgumentException _e) {
                        LOG.debug("Ignored unknown sender extended type " + e.getKey());
                    }
                }
            }

            senderIp4 = BEUtils.getBytes(payload.get(K_SENDER_IPV4));
            senderIp6 = BEUtils.getBytes(payload.get(K_SENDER_IPV6));
            senderPort = BEUtils.getInt(payload.get(K_SENDER_PORT), -1);
            senderVersion = BEUtils.getString(payload.get(K_SENDER_VERSION));
            senderRequestQueueLength = BEUtils.getInt(payload.get(K_SENDER_REQUEST_QUEUE_LENGTH), PeerHandler.MAX_REQUESTS_SENT);
            receiverIp = BEUtils.getBytes(payload.get(K_RECEIVER_IP));
        }

        @Override
        public void toWire(ByteBuf out, Map<? extends ExtendedType, ? extends Byte> extendedTypes) throws IOException {
            super.toWire(out, extendedTypes);
            NettyBEncoder encoder = new NettyBEncoder(out);
            Map<String, BEValue> payload = new HashMap<String, BEValue>();
            {
                Map<String, BEValue> types = new HashMap<String, BEValue>();
                // This is always our data set, so we could just use ordinal(), but let's not cheat; it always bites us later.
                for (Map.Entry<ExtendedType, Byte> e : senderExtendedTypeMap.entrySet())
                    types.put(e.getKey().name(), new BEValue(e.getValue().byteValue() & 0xFF));
                for (ExtendedType extendedType : ExtendedType.values())
                    types.put(extendedType.name(), new BEValue(extendedType.ordinal()));
                payload.put(K_MESSAGE_TYPES, new BEValue(types));
            }
            if (senderIp4 != null)
                payload.put(K_SENDER_IPV4, new BEValue(senderIp4));
            if (senderIp6 != null)
                payload.put(K_SENDER_IPV6, new BEValue(senderIp6));
            if (senderPort > 0)
                payload.put(K_SENDER_PORT, new BEValue(senderPort));
            if (senderVersion != null)
                payload.put(K_SENDER_PORT, new BEValue(senderVersion));
            if (senderRequestQueueLength > 0)
                payload.put(K_SENDER_REQUEST_QUEUE_LENGTH, new BEValue(senderRequestQueueLength));
            if (receiverIp != null)
                payload.put(K_RECEIVER_IP, new BEValue(receiverIp));
            encoder.bencode(payload);
        }

        @Override
        public String toString() {
            String ret = super.toString() + " " + getSenderExtendedTypeMap();
            try {
                ret = ret + " ip4=" + toAddress(senderIp4);
            } catch (UnknownHostException e) {
                ret = ret + " ip4-error=" + e;
            }
            try {
                ret = ret + " ip6=" + toAddress(senderIp6);
            } catch (UnknownHostException e) {
                ret = ret + " ip6-error=" + e;
            }
            ret = ret + " port=" + senderPort;
            return ret;
        }
    }

    public static class UtPexMessage extends PeerExtendedMessage {

        public static final String ADDED = "added";
        public static final String ADDED_F = "added.f";
        public static final String ADDED6 = "added6";
        public static final String ADDED6_F = "added6.f";
        public static final String DROPPED = "dropped";
        public static final String DROPPED6 = "dropped6";
        private final List<InetSocketAddress> added;
        private final List<InetSocketAddress> dropped;

        public UtPexMessage() {
            this.added = new ArrayList<InetSocketAddress>();
            this.dropped = new ArrayList<InetSocketAddress>();
        }

        public UtPexMessage(@Nonnull List<InetSocketAddress> added, @Nonnull List<InetSocketAddress> dropped) {
            this.added = Preconditions.checkNotNull(added, "Added was null.");
            this.dropped = Preconditions.checkNotNull(dropped, "Dropped was null.");
        }

        @Nonnull
        public List<? extends InetSocketAddress> getAdded() {
            return added;
        }

        @Nonnull
        public List<? extends InetSocketAddress> getDropped() {
            return dropped;
        }

        private static void getAddresses(@Nonnull List<InetSocketAddress> out, @CheckForNull byte[] in, @Nonnegative int size) throws UnknownHostException {
            if (in == null)
                return;
            byte[] value = new byte[size];
            int ptr = 0;
            while (ptr <= (in.length - size - 2)) {
                System.arraycopy(in, ptr, value, 0, size);
                InetAddress address = InetAddress.getByAddress(value);
                int port = Ints.fromBytes((byte) 0, (byte) 0, in[ptr + size], in[ptr + size + 1]);
                out.add(new InetSocketAddress(address, port));
                ptr = ptr + size + 2;
            }
        }

        @Override
        public void fromWire(ByteBuf in) throws IOException {
            NettyBDecoder decoder = new NettyBDecoder(in);
            Map<String, BEValue> map = decoder.bdecodeMap().getMap();

            added.clear();
            getAddresses(added, BEUtils.getBytes(map.get(ADDED)), 4);
            getAddresses(added, BEUtils.getBytes(map.get(ADDED6)), 16);

            dropped.clear();
            getAddresses(dropped, BEUtils.getBytes(map.get(DROPPED)), 4);
            getAddresses(dropped, BEUtils.getBytes(map.get(DROPPED6)), 16);
        }

        private static void put(@Nonnull Map<String, BEValue> out, @Nonnull String key, @Nonnull ByteArrayOutputStream data) {
            if (data.size() == 0)
                return;
            out.put(key, new BEValue(data.toByteArray()));
        }

        @Override
        public void toWire(ByteBuf out, Map<? extends ExtendedType, ? extends Byte> extendedTypes) throws IOException {
            super.toWire(out, extendedTypes);
            Map<String, BEValue> map = new HashMap<String, BEValue>();

            ADDED:
            {
                ByteArrayOutputStream out_added = new ByteArrayOutputStream();
                ByteArrayOutputStream out_added_f = new ByteArrayOutputStream();
                ByteArrayOutputStream out_added6 = new ByteArrayOutputStream();
                ByteArrayOutputStream out_added6_f = new ByteArrayOutputStream();

                for (InetSocketAddress socketAddress : added) {
                    InetAddress address = socketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        out_added.write(address.getAddress());
                        out_added.write(Shorts.toByteArray((short) socketAddress.getPort()));
                        out_added_f.write(0);
                    } else if (address instanceof Inet6Address) {
                        out_added6.write(address.getAddress());
                        out_added6.write(Shorts.toByteArray((short) socketAddress.getPort()));
                        out_added6_f.write(0);
                    } else {
                    }
                }

                put(map, ADDED, out_added);
                put(map, ADDED_F, out_added_f);
                put(map, ADDED6, out_added6);
                put(map, ADDED6_F, out_added6_f);
            }

            REMOVED:
            {
                ByteArrayOutputStream out_dropped = new ByteArrayOutputStream();
                ByteArrayOutputStream out_dropped6 = new ByteArrayOutputStream();

                for (InetSocketAddress socketAddress : dropped) {
                    InetAddress address = socketAddress.getAddress();
                    if (address instanceof Inet4Address) {
                        out_dropped.write(address.getAddress());
                        out_dropped.write(Shorts.toByteArray((short) socketAddress.getPort()));
                    } else if (address instanceof Inet6Address) {
                        out_dropped6.write(address.getAddress());
                        out_dropped6.write(Shorts.toByteArray((short) socketAddress.getPort()));
                    } else {
                    }
                }

                put(map, DROPPED, out_dropped);
                put(map, DROPPED6, out_dropped6);
            }

            NettyBEncoder encoder = new NettyBEncoder(out);
            encoder.bencode(map);
        }

        @Override
        public ExtendedType getExtendedType() {
            return ExtendedType.ut_pex;
        }

        @Override
        public String toString() {
            return super.toString() + "; added=" + getAdded() + "; dropped=" + getDropped();
        }
    }

    @Override
    public String toString() {
        return super.toString() + "." + getExtendedType().name();
    }
}
