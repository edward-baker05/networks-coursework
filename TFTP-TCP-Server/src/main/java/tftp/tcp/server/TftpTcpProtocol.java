package tftp.tcp.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Helper for the simplified TFTP-over-TCP protocol.
 *
 * Because TCP provides ordered, reliable delivery there are no per-block
 * acknowledgements or retransmissions — one DATA message carries the entire file.
 *
 * Wire format (all multi-byte integers big-endian):
 *
 *   RRQ / WRQ request:
 *     opcode(short=1 or 2) | filename(bytes) | 0x00 | "octet"(bytes) | 0x00
 *
 *   DATA response (server->client for RRQ, client->server for WRQ):
 *     opcode(short=3) | fileLength(long, 8 bytes) | data(fileLength bytes)
 *
 *   ERROR:
 *     opcode(short=5) | errorCode(short) | message(bytes) | 0x00
 *
 * All methods assume the opcode has already been consumed by the caller before
 * invoking readRequest / readData / readErrorCode.
 */
public final class TftpTcpProtocol {

    public static final int OP_RRQ   = 1;
    public static final int OP_WRQ   = 2;
    public static final int OP_DATA  = 3;
    public static final int OP_ERROR = 5;

    public static final int ERR_FILE_NOT_FOUND = 1;
    public static final int ERR_DISK_FULL      = 3;
    public static final int ERR_ILLEGAL_OP     = 4;

    private TftpTcpProtocol() {}

    // -------------------------------------------------------------------------
    // Reading helpers (opcode already consumed by caller)
    // -------------------------------------------------------------------------

    /**
     * Read the filename and mode fields of an RRQ/WRQ (opcode already read).
     *
     * @return String[]{filename, mode}
     */
    public static String[] readRequest(DataInputStream in) throws IOException {
        return new String[]{readNullTermString(in), readNullTermString(in)};
    }

    /**
     * Read a DATA message body (opcode already read).
     * Reads an 8-byte length prefix then exactly that many bytes.
     *
     * @return the complete file byte array
     */
    public static byte[] readData(DataInputStream in) throws IOException {
        long len = in.readLong();
        if (len < 0 || len > Integer.MAX_VALUE) {
            throw new IOException("Invalid data length: " + len);
        }
        byte[] data = new byte[(int) len];
        in.readFully(data);
        return data;
    }

    /**
     * Read the error code from an ERROR message body (opcode already read).
     * The error message string can subsequently be read with {@link #readNullTermString}.
     */
    public static int readErrorCode(DataInputStream in) throws IOException {
        return in.readShort() & 0xFFFF;
    }

    /** Read a null-terminated ASCII string from the stream. */
    public static String readNullTermString(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) > 0) {
            sb.append((char) b);
        }
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Writing helpers
    // -------------------------------------------------------------------------

    /** Write an RRQ packet. */
    public static void writeRRQ(DataOutputStream out, String filename) throws IOException {
        writeRequest(out, OP_RRQ, filename);
    }

    /** Write a WRQ packet. */
    public static void writeWRQ(DataOutputStream out, String filename) throws IOException {
        writeRequest(out, OP_WRQ, filename);
    }

    private static void writeRequest(DataOutputStream out, int opcode,
                                     String filename) throws IOException {
        out.writeShort(opcode);
        out.write(filename.getBytes(StandardCharsets.US_ASCII));
        out.writeByte(0);
        out.write("octet".getBytes(StandardCharsets.US_ASCII));
        out.writeByte(0);
        out.flush();
    }

    /**
     * Write a DATA message: opcode(2) + fileLength(8) + data bytes.
     *
     * @param data complete file content
     */
    public static void writeData(DataOutputStream out, byte[] data) throws IOException {
        out.writeShort(OP_DATA);
        out.writeLong(data.length);
        out.write(data);
        out.flush();
    }

    /** Write an ERROR message: opcode(2) + code(2) + message + NUL. */
    public static void writeError(DataOutputStream out, int code,
                                  String message) throws IOException {
        out.writeShort(OP_ERROR);
        out.writeShort(code);
        out.write(message.getBytes(StandardCharsets.US_ASCII));
        out.writeByte(0);
        out.flush();
    }
}
