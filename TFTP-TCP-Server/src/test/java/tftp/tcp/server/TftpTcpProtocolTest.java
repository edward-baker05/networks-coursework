package tftp.tcp.server;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Unit tests for {@link TftpTcpProtocol} — builder/parser round-trips. */
class TftpTcpProtocolTest {

    private static DataOutputStream dos(ByteArrayOutputStream bos) {
        return new DataOutputStream(bos);
    }

    @Test
    void writeRRQ_matchesSpec() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TftpTcpProtocol.writeRRQ(dos(bos), "foo");
        byte[] expected = {0, 1, 'f', 'o', 'o', 0, 'o', 'c', 't', 'e', 't', 0};
        assertArrayEquals(expected, bos.toByteArray());
    }

    @Test
    void writeWRQ_matchesSpec() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TftpTcpProtocol.writeWRQ(dos(bos), "foo");
        byte[] actual = bos.toByteArray();
        assertEquals(0, actual[0]);
        assertEquals(2, actual[1]);
    }

    @Test
    void writeData_prefixesOpcodeAndLongLength() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] payload = new byte[10];
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (i + 1);

        TftpTcpProtocol.writeData(dos(bos), payload);

        byte[] actual = bos.toByteArray();
        assertEquals(2 + 8 + payload.length, actual.length);
        assertEquals(0, actual[0]);
        assertEquals(3, actual[1]);     // DATA opcode
        // 8-byte big-endian length = 10
        for (int i = 2; i < 9; i++) assertEquals(0, actual[i]);
        assertEquals(10, actual[9]);
        // Body
        byte[] body = new byte[payload.length];
        System.arraycopy(actual, 10, body, 0, payload.length);
        assertArrayEquals(payload, body);
    }

    @Test
    void readData_readsExactLengthAfterOpcodeConsumed() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] payload = "hello world".getBytes(StandardCharsets.US_ASCII);
        TftpTcpProtocol.writeData(dos(bos), payload);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        int opcode = in.readShort() & 0xFFFF;
        assertEquals(TftpTcpProtocol.OP_DATA, opcode);

        byte[] read = TftpTcpProtocol.readData(in);
        assertArrayEquals(payload, read);
    }

    @Test
    void readData_rejectsNegativeLength() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = dos(bos);
        dos.writeLong(-1);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        assertThrows(IOException.class, () -> TftpTcpProtocol.readData(in));
    }

    @Test
    void readData_rejectsLengthAboveIntMax() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream dos = dos(bos);
        dos.writeLong(((long) Integer.MAX_VALUE) + 1L);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        assertThrows(IOException.class, () -> TftpTcpProtocol.readData(in));
    }

    @Test
    void writeError_encodesCodeAndNulTerminatedMessage() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TftpTcpProtocol.writeError(dos(bos), 1, "File not found");
        byte[] actual = bos.toByteArray();

        // opcode 5 | code 1 | "File not found" | 0x00
        byte[] msg = "File not found".getBytes(StandardCharsets.US_ASCII);
        assertEquals(2 + 2 + msg.length + 1, actual.length);
        assertEquals(0, actual[0]);
        assertEquals(5, actual[1]);
        assertEquals(0, actual[2]);
        assertEquals(1, actual[3]);
        for (int i = 0; i < msg.length; i++) {
            assertEquals(msg[i], actual[4 + i]);
        }
        assertEquals(0, actual[actual.length - 1]);
    }

    @Test
    void readErrorCodeAndMessage_roundTrip() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TftpTcpProtocol.writeError(dos(bos), 3, "Disk full");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        in.readShort();                               // consume opcode
        assertEquals(3, TftpTcpProtocol.readErrorCode(in));
        assertEquals("Disk full", TftpTcpProtocol.readNullTermString(in));
    }

    @Test
    void readNullTermString_stopsAtNulAndReturnsEmptyOnImmediateNul() throws IOException {
        byte[] bytes = {'A', 'B', 0, 0, 'X'};
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
        assertEquals("AB", TftpTcpProtocol.readNullTermString(in));
        assertEquals("", TftpTcpProtocol.readNullTermString(in));
    }

    @Test
    void readRequest_returnsFilenameAndMode() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        TftpTcpProtocol.writeRRQ(dos(bos), "foo");

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(bos.toByteArray()));
        in.readShort();                               // consume opcode (caller's responsibility)
        String[] req = TftpTcpProtocol.readRequest(in);

        assertEquals(2, req.length);
        assertEquals("foo", req[0]);
        assertEquals("octet", req[1]);
    }
}
