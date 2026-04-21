package tftp.tcp.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Wire protocol helpers for the simplified TFTP-over-TCP implementation.
 *
 * TCP guarantees ordered, reliable delivery so one DATA message carries the entire file.
 * Wire format (big-endian):
 *   RRQ/WRQ : opcode(2) | filename | 0x00 | "octet" | 0x00
 *   DATA    : opcode(2) | fileLength(8) | data(fileLength bytes)
 *   ERROR   : opcode(2) | errorCode(2) | message | 0x00
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
     * Reads the filename and mode from an RRQ/WRQ (opcode already consumed by caller).
     *
     * @param in the input stream positioned after the opcode.
     * @return String[] where [0] is the filename and [1] is the mode.
     */
    public static String[] readRequest(DataInputStream in) throws IOException {
        return new String[]{readNullTermString(in), readNullTermString(in)};
    }

    /**
     * Reads a DATA message body (opcode already consumed by caller).
     * An 8-byte big-endian length prefix is read first, followed by that many bytes.
     *
     * @param in the input stream positioned after the DATA opcode.
     * @return the complete file content as a byte array.
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
     * Reads the error code from an ERROR message body (opcode already consumed).
     *
     * @param in the input stream positioned after the ERROR opcode.
     * @return the 2-byte error code as an unsigned int.
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
     * Writes a DATA message: opcode(2) + length(8) + file bytes.
     *
     * @param out the output stream.
     * @param data the complete file content to send.
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
