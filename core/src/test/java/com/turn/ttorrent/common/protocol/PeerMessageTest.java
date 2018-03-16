package com.turn.ttorrent.common;

import com.turn.ttorrent.common.protocol.PeerMessage;

import org.testng.annotations.Test;

import java.nio.ByteBuffer;
import java.util.BitSet;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertEqualsNoOrder;
import static org.testng.Assert.assertTrue;


public class PeerMessageTest {

    @Test
    public void testCraftBitfieldMessage()  {
        // See https://wiki.theory.org/BitTorrentSpecification#bitfield

        // Create message with 744 (= 93 * 8) pieces
        BitSet availablePieces = new BitSet();
        availablePieces.set(0);
        availablePieces.set(700);
        availablePieces.set(743); // last piece
        availablePieces.set(744); // out of range - should be ignored
        PeerMessage.BitfieldMessage msg = PeerMessage.BitfieldMessage.craft(availablePieces, 744);

        // Check bitfield
        assertEquals(3, msg.getBitfield().cardinality());
        assertEquals(true, msg.getBitfield().get(0));
        assertEquals(true, msg.getBitfield().get(700));
        assertEquals(true, msg.getBitfield().get(743));

        // Check raw data - bitfield: <len=0001+X><id=5><bitfield>
        ByteBuffer buffer = msg.getData();

        // total size
        assertEquals(4 + 1 + 93, buffer.remaining());

        // len
        assertEquals(0, buffer.get(0));
        assertEquals(0, buffer.get(1));
        assertEquals(0, buffer.get(2));
        assertEquals(1 + 93, (int)buffer.get(3));

        // id
        assertEquals(5, buffer.get(4));

        // bitfield
        buffer.position(5);
        ByteBuffer bitfieldBuffer = buffer.slice();
        BitSet bitfield = convertByteBufferToBitfieldBitSet(bitfieldBuffer);
        assertEquals(3, bitfield.cardinality());
        assertEquals(true, bitfield.get(00));
        assertEquals(true, bitfield.get(700));
        assertEquals(true, bitfield.get(743));
    }

    @Test
    public void testCraftBitfieldMessageEmpty()  {
        // See https://wiki.theory.org/BitTorrentSpecification#bitfield

        // Create message with 744 (= 93 * 8) pieces
        BitSet availablePieces = new BitSet();
        PeerMessage.BitfieldMessage msg = PeerMessage.BitfieldMessage.craft(availablePieces, 744);

        // Check bitfield
        assertEquals(0, msg.getBitfield().cardinality());

        // Check raw data - bitfield: <len=0001+X><id=5><bitfield>
        ByteBuffer buffer = msg.getData();

        // total size
        assertEquals(4 + 1 + 93, buffer.remaining());

        // len
        assertEquals(0, buffer.get(0));
        assertEquals(0, buffer.get(1));
        assertEquals(0, buffer.get(2));
        assertEquals(1 + 93, (int)buffer.get(3));

        // id
        assertEquals(5, buffer.get(4));

        // bitfield
        buffer.position(5);
        ByteBuffer bitfieldBuffer = buffer.slice();
        BitSet bitfield = convertByteBufferToBitfieldBitSet(bitfieldBuffer);
        assertEquals(0, bitfield.cardinality());
    }

    @Test
    public void testCreateBitfieldMessageWithSparseBits()  {
        // See https://wiki.theory.org/BitTorrentSpecification#bitfield

        // Create message with 745 (= 93 * 8 + 1) pieces
        BitSet availablePieces = new BitSet();
        availablePieces.set(10);
        availablePieces.set(700);
        availablePieces.set(744);
        availablePieces.set(745); // out of range - should be ignored
        PeerMessage.BitfieldMessage msg = PeerMessage.BitfieldMessage.craft(availablePieces, 745);

        // Check bitfield
        assertEquals(3, msg.getBitfield().cardinality());
        assertEquals(true, msg.getBitfield().get(10));
        assertEquals(true, msg.getBitfield().get(700));
        assertEquals(true, msg.getBitfield().get(744));

        // Check raw data - bitfield: <len=0001+X><id=5><bitfield>
        ByteBuffer buffer = msg.getData();

        // total size
        assertEquals(4 + 1 + 94, buffer.remaining());

        // len
        assertEquals(0, buffer.get(0));
        assertEquals(0, buffer.get(1));
        assertEquals(0, buffer.get(2));
        assertEquals(1 + 94, (int)buffer.get(3));

        // id
        assertEquals(5, buffer.get(4));

        // bitfield with 7 spare bits
        buffer.position(5);
        ByteBuffer bitfieldBuffer = buffer.slice();
        BitSet bitfield = convertByteBufferToBitfieldBitSet(bitfieldBuffer);
        assertEquals(3, bitfield.cardinality());
        assertEquals(true, bitfield.get(10));
        assertEquals(true, bitfield.get(700));
        assertEquals(true, bitfield.get(744));
    }

    private BitSet convertByteBufferToBitfieldBitSet(ByteBuffer buffer) {
        BitSet bitfield = new BitSet();
        for (int i=0; i < buffer.remaining()*8; i++) {
            if ((buffer.get(i/8) & (1 << (7 -(i % 8)))) > 0) {
                bitfield.set(i);
            }
        }
        return bitfield;
    }

}
