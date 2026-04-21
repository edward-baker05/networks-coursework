package tftp.udp.server;

import java.net.DatagramPacket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Unit tests for {@link TftpPacket}. Covers the byte-level packet layout
 * demanded by RFC 1350 §5: packet header (opcodes), packetisation (block
 * sizes and numbering), and all builder/parser round-trips.
 */
class TftpPacketTest {

    // ------------------------------------------------------------------
    // Builders — Packet Header (video §1) + Packetisation (video §2)
    // ------------------------------------------------------------------

    @Test
    void buildRRQ_producesOpcode1PlusFilenamePlusOctet() {
        byte[] actual = TftpPacket.buildRRQ("foo");
        byte[] expected = {0, 1, 'f', 'o', 'o', 0, 'o', 'c', 't', 'e', 't', 0};
        assertArrayEquals(expected, actual);
    }

    @Test
    void buildWRQ_producesOpcode2PlusFilenamePlusOctet() {
        byte[] actual = TftpPacket.buildWRQ("foo");
        assertEquals(0, actual[0]);
        assertEquals(2, actual[1]);
        // Remainder identical to RRQ (same layout)
        byte[] tail = {'f', 'o', 'o', 0, 'o', 'c', 't', 'e', 't', 0};
        for (int i = 0; i < tail.length; i++) {
            assertEquals(tail[i], actual[i + 2], "byte " + (i + 2));
        }
    }

    @Test
    void buildData_withFullBlock_producesHeaderPlus512Bytes() {
        byte[] payload = new byte[TftpPacket.BLOCK_SIZE];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i & 0xFF);

        byte[] pkt = TftpPacket.buildData(1, payload, 0, payload.length);

        assertEquals(4 + TftpPacket.BLOCK_SIZE, pkt.length);
        assertEquals(0, pkt[0]);
        assertEquals(3, pkt[1]);   // DATA opcode
        assertEquals(0, pkt[2]);
        assertEquals(1, pkt[3]);   // block number 1, big-endian
        byte[] body = new byte[TftpPacket.BLOCK_SIZE];
        System.arraycopy(pkt, 4, body, 0, TftpPacket.BLOCK_SIZE);
        assertArrayEquals(payload, body);
    }

    @Test
    void buildData_withShortFinalBlock_producesHeaderPlusNBytes() {
        byte[] payload = new byte[100];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) i;

        byte[] pkt = TftpPacket.buildData(42, payload, 0, payload.length);

        assertEquals(104, pkt.length);
        assertEquals(3, pkt[1]);
        assertEquals(0, pkt[2]);
        assertEquals(42, pkt[3]);
    }

    @Test
    void buildData_withZeroBytes_producesFourByteHeaderOnly() {
        byte[] pkt = TftpPacket.buildData(7, new byte[0], 0, 0);
        assertEquals(4, pkt.length);
        assertEquals(3, pkt[1]);
        assertEquals(7, pkt[3]);
    }

    @Test
    void buildData_blockNumberWrapsAtSixtyFiveThousand() {
        byte[] wrap = TftpPacket.buildData(65536, new byte[0], 0, 0);
        assertEquals(0, wrap[2]);
        assertEquals(0, wrap[3]);

        byte[] max = TftpPacket.buildData(65535, new byte[0], 0, 0);
        assertEquals((byte) 0xFF, max[2]);
        assertEquals((byte) 0xFF, max[3]);
    }

    @Test
    void buildAck_isFourBytes() {
        byte[] ack = TftpPacket.buildAck(0x1234);
        assertEquals(4, ack.length);
        assertEquals(0, ack[0]);
        assertEquals(4, ack[1]);        // ACK opcode
        assertEquals(0x12, ack[2] & 0xFF);
        assertEquals(0x34, ack[3] & 0xFF);
    }

    @Test
    void buildError_encodesCodeAndNullTerminatedMessage() {
        byte[] err = TftpPacket.buildError(1, "File not found");

        // opcode 5, code 1, message bytes, null terminator
        byte[] msg = "File not found".getBytes(StandardCharsets.US_ASCII);
        assertEquals(4 + msg.length + 1, err.length);
        assertEquals(0, err[0]);
        assertEquals(5, err[1]);     // ERROR opcode
        assertEquals(0, err[2]);
        assertEquals(1, err[3]);     // code 1
        for (int i = 0; i < msg.length; i++) {
            assertEquals(msg[i], err[4 + i], "message byte " + i);
        }
        assertEquals(0, err[err.length - 1]);
    }

    // ------------------------------------------------------------------
    // Parsers
    // ------------------------------------------------------------------

    @Test
    void getOpcode_parsesBigEndian() {
        byte[] buf = {0, 5, 0, 1, 'x', 0};
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        assertEquals(5, TftpPacket.getOpcode(pkt));
    }

    @Test
    void getBlockNumber_parsesBigEndian() {
        byte[] buf = {0, 3, 0x12, 0x34};
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        assertEquals(0x1234, TftpPacket.getBlockNumber(pkt));
    }

    @Test
    void getData_returnsPayloadOnly() {
        byte[] body = "hello-tftp-world!".getBytes(StandardCharsets.US_ASCII);
        byte[] pkt = TftpPacket.buildData(1, body, 0, body.length);

        DatagramPacket dp = new DatagramPacket(pkt, pkt.length);
        byte[] out = TftpPacket.getData(dp);

        assertEquals(body.length, out.length);
        assertArrayEquals(body, out);
    }

    @Test
    void getData_returnsEmptyForHeaderOnlyPacket() {
        byte[] pkt = TftpPacket.buildData(3, new byte[0], 0, 0);
        DatagramPacket dp = new DatagramPacket(pkt, pkt.length);
        assertEquals(0, TftpPacket.getData(dp).length);
    }

    @Test
    void parseRequest_extractsFilenameAndMode() {
        byte[] rrq = TftpPacket.buildRRQ("foo");
        DatagramPacket dp = new DatagramPacket(rrq, rrq.length);
        String[] parsed = TftpPacket.parseRequest(dp);

        assertEquals(2, parsed.length);
        assertEquals("foo", parsed[0]);
        assertEquals("octet", parsed[1]);
    }

    @Test
    void getErrorCode_parsesBigEndian() {
        byte[] pkt = TftpPacket.buildError(1, "nope");
        DatagramPacket dp = new DatagramPacket(pkt, pkt.length);
        assertEquals(1, TftpPacket.getErrorCode(dp));
    }

    @Test
    void getErrorMessage_stripsNullTerminator() {
        byte[] pkt = TftpPacket.buildError(3, "Disk full");
        DatagramPacket dp = new DatagramPacket(pkt, pkt.length);
        assertEquals("Disk full", TftpPacket.getErrorMessage(dp));
    }

    // ------------------------------------------------------------------
    // Round-trip — build then parse back
    // ------------------------------------------------------------------

    @Test
    void roundTripAllPacketTypes() {
        // RRQ
        byte[] rrq = TftpPacket.buildRRQ("file.bin");
        DatagramPacket rrqPkt = new DatagramPacket(rrq, rrq.length);
        assertEquals(TftpPacket.OP_RRQ, TftpPacket.getOpcode(rrqPkt));
        assertEquals("file.bin", TftpPacket.parseRequest(rrqPkt)[0]);
        assertEquals("octet", TftpPacket.parseRequest(rrqPkt)[1]);

        // WRQ
        byte[] wrq = TftpPacket.buildWRQ("file.bin");
        DatagramPacket wrqPkt = new DatagramPacket(wrq, wrq.length);
        assertEquals(TftpPacket.OP_WRQ, TftpPacket.getOpcode(wrqPkt));

        // DATA
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] data = TftpPacket.buildData(9, payload, 0, payload.length);
        DatagramPacket dataPkt = new DatagramPacket(data, data.length);
        assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(dataPkt));
        assertEquals(9, TftpPacket.getBlockNumber(dataPkt));
        assertArrayEquals(payload, TftpPacket.getData(dataPkt));

        // ACK
        byte[] ack = TftpPacket.buildAck(9);
        DatagramPacket ackPkt = new DatagramPacket(ack, ack.length);
        assertEquals(TftpPacket.OP_ACK, TftpPacket.getOpcode(ackPkt));
        assertEquals(9, TftpPacket.getBlockNumber(ackPkt));

        // ERROR
        byte[] err = TftpPacket.buildError(1, "File not found");
        DatagramPacket errPkt = new DatagramPacket(err, err.length);
        assertEquals(TftpPacket.OP_ERROR, TftpPacket.getOpcode(errPkt));
        assertEquals(1, TftpPacket.getErrorCode(errPkt));
        assertEquals("File not found", TftpPacket.getErrorMessage(errPkt));
    }
}
