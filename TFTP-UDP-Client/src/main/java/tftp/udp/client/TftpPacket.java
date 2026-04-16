package tftp.udp.client;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/**
 * TFTP packet builder and parser (RFC 1350).
 *
 * Packet layouts (all multi-byte integers are big-endian):
 *
 *  RRQ/WRQ  : opcode(2) | filename | 0x00 | "octet" | 0x00
 *  DATA     : opcode(2) | block#(2) | data(0-512)
 *  ACK      : opcode(2) | block#(2)
 *  ERROR    : opcode(2) | errorCode(2) | message | 0x00
 */
public final class TftpPacket {

    // Opcodes
    public static final int OP_RRQ   = 1;
    public static final int OP_WRQ   = 2;
    public static final int OP_DATA  = 3;
    public static final int OP_ACK   = 4;
    public static final int OP_ERROR = 5;

    // Error codes
    public static final int ERR_NOT_DEFINED      = 0;
    public static final int ERR_FILE_NOT_FOUND   = 1;
    public static final int ERR_ACCESS_VIOLATION = 2;
    public static final int ERR_DISK_FULL        = 3;
    public static final int ERR_ILLEGAL_OP       = 4;
    public static final int ERR_UNKNOWN_TID      = 5;
    public static final int ERR_FILE_EXISTS      = 6;
    public static final int ERR_NO_SUCH_USER     = 7;

    /** Maximum data bytes per DATA block (fixed by RFC). */
    public static final int BLOCK_SIZE = 512;

    /** Maximum total size of any TFTP packet: 4-byte header + 512 data bytes. */
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
     * Build a DATA packet.
     *
     * @param blockNumber 16-bit block number (1-based, wraps at 65535 -> 0)
     * @param data        source byte array
     * @param offset      offset within {@code data}
     * @param length      number of bytes to include (0..512)
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

    /** Build a 4-byte ACK packet for the given block number. */
    public static byte[] buildAck(int blockNumber) {
        byte[] buf = new byte[4];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        bb.putShort((short) OP_ACK);
        bb.putShort((short) (blockNumber & 0xFFFF));
        return buf;
    }

    /** Build an ERROR packet. */
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

    /** Return the 2-byte opcode from a received packet. */
    public static int getOpcode(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int o = pkt.getOffset();
        return ((d[o] & 0xFF) << 8) | (d[o + 1] & 0xFF);
    }

    /** Return the block number from a DATA or ACK packet (as an unsigned 16-bit value). */
    public static int getBlockNumber(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int o = pkt.getOffset();
        return ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** Copy and return the data payload from a DATA packet (0..512 bytes). */
    public static byte[] getData(DatagramPacket pkt) {
        int dataLen = pkt.getLength() - 4;
        if (dataLen <= 0) return new byte[0];
        byte[] result = new byte[dataLen];
        System.arraycopy(pkt.getData(), pkt.getOffset() + 4, result, 0, dataLen);
        return result;
    }

    /** Return the byte count of the data payload in a DATA packet. */
    public static int getDataLength(DatagramPacket pkt) {
        return Math.max(0, pkt.getLength() - 4);
    }

    /**
     * Parse filename and mode from an RRQ or WRQ packet.
     *
     * @return String array: [0] = filename, [1] = mode
     */
    public static String[] parseRequest(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int start = pkt.getOffset() + 2; // skip 2-byte opcode
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

    /** Return the error code from an ERROR packet. */
    public static int getErrorCode(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int o = pkt.getOffset();
        return ((d[o + 2] & 0xFF) << 8) | (d[o + 3] & 0xFF);
    }

    /** Return the null-terminated error message from an ERROR packet. */
    public static String getErrorMessage(DatagramPacket pkt) {
        byte[] d = pkt.getData();
        int start = pkt.getOffset() + 4;
        int end   = pkt.getOffset() + pkt.getLength();
        int i = start;
        while (i < end && d[i] != 0) i++;
        return new String(d, start, i - start, StandardCharsets.US_ASCII);
    }
}
