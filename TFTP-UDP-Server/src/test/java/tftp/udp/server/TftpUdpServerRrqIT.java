package tftp.udp.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.HexFormat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * End-to-end integration tests for the UDP server's RRQ path.
 * Covers RFC 1350 §5 (packet layouts), §4.2.3 timeouts/retransmits,
 * §2 TID isolation, and the error-code-1 file-not-found flow.
 */
class TftpUdpServerRrqIT {

    @TempDir
    Path serverDir;

    private TftpUdpServer.ServerHandle server;
    private InetAddress localhost;

    @BeforeEach
    void startServer() throws Exception {
        server = TftpUdpServer.start(0, serverDir);
        localhost = InetAddress.getByName("127.0.0.1");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    // ------------------------------------------------------------------
    // Small + short-final-block transfers
    // ------------------------------------------------------------------

    @Test
    void rrq_smallFile_returnsOneDataAndEndsAfterAck() throws Exception {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i + 1);
        Files.write(serverDir.resolve("small.bin"), body);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("small.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket dataPkt = receive(client);
            assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(dataPkt));
            assertEquals(1, TftpPacket.getBlockNumber(dataPkt));
            assertArrayEquals(body, TftpPacket.getData(dataPkt));

            sendAck(client, 1, dataPkt.getAddress(), dataPkt.getPort());
        }
    }

    @Test
    void rrq_exactMultipleOf512_emitsEmptyFinalBlock() throws Exception {
        byte[] body = new byte[1024];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        Files.write(serverDir.resolve("x2.bin"), body);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("x2.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket b1 = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(b1));
            assertEquals(TftpPacket.BLOCK_SIZE, TftpPacket.getData(b1).length);
            sendAck(client, 1, b1.getAddress(), b1.getPort());

            DatagramPacket b2 = receive(client);
            assertEquals(2, TftpPacket.getBlockNumber(b2));
            assertEquals(TftpPacket.BLOCK_SIZE, TftpPacket.getData(b2).length);
            sendAck(client, 2, b2.getAddress(), b2.getPort());

            DatagramPacket b3 = receive(client);
            assertEquals(3, TftpPacket.getBlockNumber(b3));
            assertEquals(0, TftpPacket.getData(b3).length);
            sendAck(client, 3, b3.getAddress(), b3.getPort());
        }
    }

    @Test
    void rrq_mediumTxt_transfersVerbatim() throws Exception {
        Path source = Paths.get("..", "medium.txt").toAbsolutePath().normalize();
        if (!Files.exists(source)) return;                        // skip if corpus missing

        byte[] original = Files.readAllBytes(source);
        Files.copy(source, serverDir.resolve("medium.txt"));

        byte[] received = runRrq("medium.txt");
        assertArrayEquals(original, received);
    }

    @Test
    void rrq_largeTxt_transfersVerbatim() throws Exception {
        Path source = Paths.get("..", "large.txt").toAbsolutePath().normalize();
        if (!Files.exists(source)) return;

        byte[] original = Files.readAllBytes(source);
        Files.copy(source, serverDir.resolve("large.txt"));

        byte[] received = runRrq("large.txt");
        assertArrayEquals(original, received);
    }

    @Test
    void rrq_binaryFile_transfersOctetExact() throws Exception {
        Path source = Paths.get("..", "test_image-2.jpg").toAbsolutePath().normalize();
        if (!Files.exists(source)) return;

        byte[] original = Files.readAllBytes(source);
        Files.copy(source, serverDir.resolve("img.jpg"));

        byte[] received = runRrq("img.jpg");
        assertEquals(sha256(original), sha256(received));
    }

    // ------------------------------------------------------------------
    // Error handling (video §4)
    // ------------------------------------------------------------------

    @Test
    void rrq_missingFile_returnsError1() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("does-not-exist.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket err = receive(client);
            assertEquals(TftpPacket.OP_ERROR, TftpPacket.getOpcode(err));
            assertEquals(TftpPacket.ERR_FILE_NOT_FOUND, TftpPacket.getErrorCode(err));
            assertEquals("File not found", TftpPacket.getErrorMessage(err));
        }
    }

    // ------------------------------------------------------------------
    // TID isolation (RFC 1350 §4)
    // ------------------------------------------------------------------

    @Test
    void rrq_usesNewTidPerTransfer() throws Exception {
        Files.write(serverDir.resolve("tidcheck.bin"), new byte[10]);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("tidcheck.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket dataPkt = receive(client);
            int transferPort = dataPkt.getPort();
            assertNotEquals(server.port(), transferPort,
                            "server must use a fresh ephemeral socket for the transfer");

            sendAck(client, 1, dataPkt.getAddress(), transferPort);
        }
    }

    // ------------------------------------------------------------------
    // Timeouts / retransmits (video §4, rubric: timeouts)
    // ------------------------------------------------------------------

    @Test
    void rrq_droppedAckTriggersRetransmit() throws Exception {
        byte[] body = new byte[50];
        Files.write(serverDir.resolve("retr.bin"), body);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(6000);

            byte[] rrq = TftpPacket.buildRRQ("retr.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket first = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(first));
            // Deliberately do NOT send ACK(1); wait for the retransmit.

            DatagramPacket second = receive(client);
            assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(second));
            assertEquals(1, TftpPacket.getBlockNumber(second));

            sendAck(client, 1, second.getAddress(), second.getPort());
        }
    }

    // ------------------------------------------------------------------
    // Unknown TID error (RFC 1350 §4, error code 5)
    // ------------------------------------------------------------------

    @Test
    void rrq_unknownTidSenderGetsError5() throws Exception {
        byte[] body = new byte[TftpPacket.BLOCK_SIZE + 20]; // force two blocks
        Files.write(serverDir.resolve("two.bin"), body);

        try (DatagramSocket client = new DatagramSocket();
             DatagramSocket intruder = new DatagramSocket()) {

            client.setSoTimeout(6000);
            intruder.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("two.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket b1 = receive(client);
            int transferPort = b1.getPort();

            // Intruder sends a bogus ACK to the transfer port
            byte[] bogus = TftpPacket.buildAck(1);
            intruder.send(new DatagramPacket(bogus, bogus.length, localhost, transferPort));

            // Intruder should get an ERROR(5) back
            DatagramPacket err = receive(intruder);
            assertEquals(TftpPacket.OP_ERROR, TftpPacket.getOpcode(err));
            assertEquals(TftpPacket.ERR_UNKNOWN_TID, TftpPacket.getErrorCode(err));

            // The real transfer must still complete
            sendAck(client, 1, localhost, transferPort);
            DatagramPacket b2 = receive(client);
            assertEquals(2, TftpPacket.getBlockNumber(b2));
            sendAck(client, 2, localhost, transferPort);
        }
    }

    // ------------------------------------------------------------------
    // Completion detection (video §5)
    // ------------------------------------------------------------------

    @Test
    void rrq_finalAckDetection() throws Exception {
        byte[] body = new byte[10];
        Files.write(serverDir.resolve("tiny.bin"), body);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(5000);

            byte[] rrq = TftpPacket.buildRRQ("tiny.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket data = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(data));
            assertTrue(TftpPacket.getData(data).length < TftpPacket.BLOCK_SIZE);

            sendAck(client, 1, data.getAddress(), data.getPort());

            // After the final ACK the server must NOT send anything else
            // within a full ~2× timeout window (default server timeout = 2s).
            client.setSoTimeout(4500);
            assertThrows(SocketTimeoutException.class, () -> {
                byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
                client.receive(new DatagramPacket(buf, buf.length));
            });
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Drive a full RRQ from the client side and return the concatenated payload. */
    private byte[] runRrq(String filename) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(5000);
            byte[] rrq = TftpPacket.buildRRQ(filename);
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            java.io.ByteArrayOutputStream acc = new java.io.ByteArrayOutputStream();
            int expected = 1;
            InetAddress transferAddr = null;
            int transferPort = -1;

            while (true) {
                DatagramPacket pkt = receive(client);
                if (transferAddr == null) {
                    transferAddr = pkt.getAddress();
                    transferPort = pkt.getPort();
                }

                if (TftpPacket.getOpcode(pkt) != TftpPacket.OP_DATA) {
                    fail("expected DATA, got opcode " + TftpPacket.getOpcode(pkt));
                }
                assertEquals(expected, TftpPacket.getBlockNumber(pkt));

                byte[] payload = TftpPacket.getData(pkt);
                acc.write(payload);

                sendAck(client, expected, transferAddr, transferPort);

                if (payload.length < TftpPacket.BLOCK_SIZE) break;
                expected++;
            }
            return acc.toByteArray();
        }
    }

    private DatagramPacket receive(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        return pkt;
    }

    private void sendAck(DatagramSocket socket, int block,
                         InetAddress addr, int port) throws Exception {
        byte[] ack = TftpPacket.buildAck(block);
        socket.send(new DatagramPacket(ack, ack.length, addr, port));
    }

    private static String sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }
}
