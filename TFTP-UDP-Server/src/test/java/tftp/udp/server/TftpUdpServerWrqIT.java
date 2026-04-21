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
                            "server must use a fresh ephemeral socket for each transfer");

            sendData(client, 1, new byte[0], 0, 0, ack0.getPort());
            receive(client);
        }
    }

    @Test
    void wrq_droppedDataTriggersAckResend() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(7000);

            byte[] wrq = TftpPacket.buildWRQ("u-f1.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0a = receive(client);
            assertEquals(0, TftpPacket.getBlockNumber(ack0a));
            int transferPort = ack0a.getPort();

            // Do not send DATA(1); wait for the server to resend ACK(0).
            DatagramPacket ack0b = receive(client);
            assertEquals(TftpPacket.OP_ACK, TftpPacket.getOpcode(ack0b));
            assertEquals(0, TftpPacket.getBlockNumber(ack0b));

            byte[] body = {1, 2, 3, 4};
            sendData(client, 1, body, 0, body.length, transferPort);
            DatagramPacket ack1 = receive(client);
            assertEquals(1, TftpPacket.getBlockNumber(ack1));
        }

        assertArrayEquals(new byte[]{1, 2, 3, 4},
                          Files.readAllBytes(serverDir.resolve("u-f1.bin")));
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
