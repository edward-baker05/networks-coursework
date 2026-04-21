package tftp.udp.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration tests for the UDP server's WRQ (upload) path.
 * Covers block-at-a-time ACK, timeouts/retransmits (the F1 fix guards this),
 * duplicate-block handling, TID isolation, and the final-block rule.
 */
class TftpUdpServerWrqIT {

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

    @Test
    void wrq_smallFile_writesToServerDirAndEndsAfterAck() throws Exception {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i + 1);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] wrq = TftpPacket.buildWRQ("u-small.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0 = receive(client);
            assertEquals(TftpPacket.OP_ACK, TftpPacket.getOpcode(ack0));
            assertEquals(0, TftpPacket.getBlockNumber(ack0));
            int transferPort = ack0.getPort();

            byte[] data = TftpPacket.buildData(1, body, 0, body.length);
            client.send(new DatagramPacket(data, data.length, localhost, transferPort));

            DatagramPacket ack1 = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(ack1));
        }

        assertArrayEquals(body, Files.readAllBytes(serverDir.resolve("u-small.bin")));
    }

    @Test
    void wrq_exactMultipleOf512_requiresEmptyFinalBlock() throws Exception {
        byte[] body = new byte[1024];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] wrq = TftpPacket.buildWRQ("u-x2.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0 = receive(client);
            int transferPort = ack0.getPort();

            sendData(client, 1, body, 0, 512, transferPort);
            assertEquals(1, TftpPacket.getBlockNumber(receive(client)));

            sendData(client, 2, body, 512, 512, transferPort);
            assertEquals(2, TftpPacket.getBlockNumber(receive(client)));

            sendData(client, 3, new byte[0], 0, 0, transferPort);
            assertEquals(3, TftpPacket.getBlockNumber(receive(client)));
        }

        assertArrayEquals(body, Files.readAllBytes(serverDir.resolve("u-x2.bin")));
    }

    @Test
    void wrq_mediumTxt_roundTripsBytes() throws Exception {
        Path source = Paths.get("..", "medium.txt").toAbsolutePath().normalize();
        if (!Files.exists(source)) return;

        byte[] original = Files.readAllBytes(source);
        runWrq("medium.txt", original);
        assertArrayEquals(original, Files.readAllBytes(serverDir.resolve("medium.txt")));
    }

    @Test
    void wrq_usesNewTidPerTransfer() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] wrq = TftpPacket.buildWRQ("tid.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0 = receive(client);
            assertNotEquals(server.port(), ack0.getPort(),
                            "server must use a fresh ephemeral socket for the transfer");

            // Complete the transfer so the handler can close cleanly.
            sendData(client, 1, new byte[0], 0, 0, ack0.getPort());
            receive(client);
        }
    }

    // ------------------------------------------------------------------
    // F1 regression: if the client drops DATA after ACK(0), server must
    // retransmit the last-sent ACK rather than silently aborting.
    // ------------------------------------------------------------------

    @Test
    void wrq_droppedDataTriggersAckResend() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(7000);

            byte[] wrq = TftpPacket.buildWRQ("u-f1.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0a = receive(client);
            assertEquals(0, TftpPacket.getBlockNumber(ack0a));
            int transferPort = ack0a.getPort();

            // Do NOT send DATA(1); wait for the server to resend ACK(0)
            DatagramPacket ack0b = receive(client);
            assertEquals(TftpPacket.OP_ACK, TftpPacket.getOpcode(ack0b));
            assertEquals(0, TftpPacket.getBlockNumber(ack0b));
            assertEquals(transferPort, ack0b.getPort());

            // Now complete the transfer
            byte[] body = {1, 2, 3, 4};
            sendData(client, 1, body, 0, body.length, transferPort);
            DatagramPacket ack1 = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(ack1));
        }

        assertArrayEquals(new byte[]{1, 2, 3, 4},
                          Files.readAllBytes(serverDir.resolve("u-f1.bin")));
    }

    // ------------------------------------------------------------------
    // Duplicate DATA block is re-acked but not double-written
    // ------------------------------------------------------------------

    @Test
    void wrq_duplicateDataIsAckedNotWritten() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] wrq = TftpPacket.buildWRQ("dup.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            int transferPort = receive(client).getPort();

            // Force a multi-block transfer so the server has a "previous block" to duplicate.
            byte[] block1 = new byte[512];
            for (int i = 0; i < block1.length; i++) block1[i] = (byte) i;
            byte[] block2 = {9, 9, 9};

            sendData(client, 1, block1, 0, block1.length, transferPort);
            assertEquals(1, TftpPacket.getBlockNumber(receive(client)));

            // Resend block 1 → server must re-ACK without corrupting state.
            sendData(client, 1, block1, 0, block1.length, transferPort);
            DatagramPacket dupAck = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(dupAck));

            // Now send the final block and confirm the written file is correct.
            sendData(client, 2, block2, 0, block2.length, transferPort);
            assertEquals(2, TftpPacket.getBlockNumber(receive(client)));
        }

        byte[] expected = new byte[512 + 3];
        for (int i = 0; i < 512; i++) expected[i] = (byte) i;
        expected[512] = 9; expected[513] = 9; expected[514] = 9;
        assertArrayEquals(expected, Files.readAllBytes(serverDir.resolve("dup.bin")));
    }

    @Test
    void wrq_unknownTidSenderGetsError5() throws Exception {
        try (DatagramSocket client = new DatagramSocket();
             DatagramSocket intruder = new DatagramSocket()) {

            client.setSoTimeout(4000);
            intruder.setSoTimeout(3000);

            byte[] wrq = TftpPacket.buildWRQ("wrq-tid.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            int transferPort = receive(client).getPort();

            // Intruder sends noise to the transfer port mid-session
            byte[] bogus = TftpPacket.buildData(1, new byte[]{0x55}, 0, 1);
            intruder.send(new DatagramPacket(bogus, bogus.length, localhost, transferPort));

            DatagramPacket err = receive(intruder);
            assertEquals(TftpPacket.OP_ERROR, TftpPacket.getOpcode(err));
            assertEquals(TftpPacket.ERR_UNKNOWN_TID, TftpPacket.getErrorCode(err));

            // Real transfer proceeds
            byte[] body = {1, 2, 3};
            sendData(client, 1, body, 0, body.length, transferPort);
            assertTrue(TftpPacket.getBlockNumber(receive(client)) == 1);
        }

        assertArrayEquals(new byte[]{1, 2, 3},
                          Files.readAllBytes(serverDir.resolve("wrq-tid.bin")));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void runWrq(String filename, byte[] body) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(10000);

            byte[] wrq = TftpPacket.buildWRQ(filename);
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0 = receive(client);
            int transferPort = ack0.getPort();

            int offset = 0;
            int blockNum = 1;
            boolean done = false;
            while (!done) {
                int len = Math.min(TftpPacket.BLOCK_SIZE, body.length - offset);
                done = (len < TftpPacket.BLOCK_SIZE);
                sendData(client, blockNum, body, offset, len, transferPort);
                assertEquals(blockNum, TftpPacket.getBlockNumber(receive(client)));
                offset += len;
                blockNum++;
            }
        }
    }

    private DatagramPacket receive(DatagramSocket socket) throws Exception {
        byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        return pkt;
    }

    private void sendData(DatagramSocket socket, int block,
                          byte[] body, int offset, int len, int port) throws Exception {
        byte[] pkt = TftpPacket.buildData(block, body, offset, len);
        socket.send(new DatagramPacket(pkt, pkt.length, localhost, port));
    }
}
