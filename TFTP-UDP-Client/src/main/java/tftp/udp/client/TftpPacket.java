package tftp.udp.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * Builds and parses TFTP packets (RFC 1350, octet mode).
 *
 * Wire format — all multi-byte integers are big-endian:
 *   RRQ/WRQ : opcode(2) | filename | 0x00 | "octet" | 0x00
 *   DATA    : opcode(2) | block#(2) | data(0-512)
 *   ACK     : opcode(2) | block#(2)
 *   ERROR   : opcode(2) | errorCode(2) | message | 0x00
 */
public final class TftpPacket {

    public static final int OP_RRQ   = 1;
    public static final int OP_WRQ   = 2;
    public static final int OP_DATA  = 3;
    public static final int OP_ACK   = 4;
    public static final int OP_ERROR = 5;

    public static final int ERR_FILE_NOT_FOUND = 1;

    public static final int BLOCK_SIZE      = 512;
    public static final int MAX_PACKET_SIZE = 4 + BLOCK_SIZE;

    private TftpPacket() {}

    // -------------------------------------------------------------------------
    // Builders
    // -------------------------------------------------------------------------

    public static byte[] buildRRQ(String filename) {
        return buildRequest(OP_RRQ, filename);
    }

    public static byte[] buildWRQ(String filename) {
        return buildRequest(OP_WRQ, filename);
    }

    private static byte[] buildRequest(int opcode, String filename) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeShort(opcode);
            dos.write(filename.getBytes(StandardCharsets.US_ASCII));
            dos.writeByte(0);
            dos.write("octet".getBytes(StandardCharsets.US_ASCII));
            dos.writeByte(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    /**
     * Builds a DATA packet with a 4-byte header and up to 512 bytes of content.
     *
     * @param blockNumber the 1-based block sequence number.
     * @param data source byte array.
     * @param offset start index within {@code data}.
     * @param length number of bytes to include (0–512).
     * @return the assembled DATA packet as a byte array.
     */
    public static byte[] buildData(int blockNumber, byte[] data, int offset, int length) {
        byte[] buf = new byte[4 + length];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putShort((short) OP_DATA);
        bb.putShort((short) (blockNumber & 0xFFFF));
        if (length > 0) {
            bb.put(data, offset, length);
        }
        return buf;
    }

    /** Builds a 4-byte ACK packet for the given block number. */
    public static byte[] buildAck(int blockNumber) {
        byte[] buf = new byte[4];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putShort((short) OP_ACK);
        bb.putShort((short) (blockNumber & 0xFFFF));
        return buf;
    }

    /** Builds an ERROR packet with the given error code and message. */
    public static byte[] buildError(int errorCode, String message) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (DataOutputStream dos = new DataOutputStream(bos)) {
            dos.writeShort(OP_ERROR);
            dos.writeShort(errorCode);
            dos.write(message.getBytes(StandardCharsets.US_ASCII));
            dos.writeByte(0);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }

    // -------------------------------------------------------------------------
    // Parsers
    // -------------------------------------------------------------------------

    /** Returns the opcode from a received packet. */
    public static int getOpcode(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int o = pkt.getOffset();
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    /** Returns the block number from a DATA or ACK packet. */
    public static int getBlockNumber(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int o = pkt.getOffset();
        return ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** Returns a copy of the data payload from a DATA packet (0–512 bytes). */
    public static byte[] getData(DatagramPacket pkt) {
        int dataLen = pkt.getLength() - 4;
        if (dataLen <= 0) return new byte[0];
        byte[] result = new byte[dataLen];
        System.arraycopy(pkt.getData(), pkt.getOffset() + 4, result, 0, dataLen);
        return result;
    }

    /**
     * Parses the filename and mode from an RRQ or WRQ packet.
     *
     * @param pkt the received datagram.
     * @return String[] where [0] is the filename and [1] is the mode.
     */
    public static String[] parseRequest(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int start = pkt.getOffset() + 2;
        int end   = pkt.getOffset() + pkt.getLength();

        int i = start;
        while (i < end && d[i] != 0) i++;
        String filename = new String(d, start, i - start, StandardCharsets.US_ASCII);

        int j = i + 1;
        int k = j;
        while (k < end && d[k] != 0) k++;
        String mode = new String(d, j, k - j, StandardCharsets.US_ASCII);

        return new String[]{filename, mode};
    }

    /** Returns the error code from an ERROR packet. */
    public static int getErrorCode(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int o = pkt.getOffset();
        return ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** Returns the null-terminated error message from an ERROR packet. */
    public static String getErrorMessage(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int start = pkt.getOffset() + 4;
        int end   = pkt.getOffset() + pkt.getLength();
        int i = start;
        while (i < end && d[i] != 0) i++;
        return new String(d, start, i - start, StandardCharsets.US_ASCII);
    }
}
