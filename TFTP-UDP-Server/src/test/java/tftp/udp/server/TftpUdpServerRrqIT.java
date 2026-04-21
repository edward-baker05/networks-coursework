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
        if (!Files.exists(source)) return;

        byte[] original = Files.readAllBytes(source);
        Files.copy(source, serverDir.resolve("medium.txt"));

        byte[] received = runRrq("medium.txt");
        assertArrayEquals(original, received);
    }

    @Test
    void rrq_missingFile_returnsError1() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("does-not-exist.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            DatagramPacket err = receive(client);
            assertEquals(TftpPacket.OP_ERROR, TftpPacket.getOpcode(err));
            assertEquals(TftpPacket.ERR_FILE_NOT_FOUND, TftpPacket.getErrorCode(err));
        }
    }

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
                            "server must use a fresh ephemeral socket for each transfer");

            sendAck(client, 1, dataPkt.getAddress(), transferPort);
        }
    }

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
            // Do not ACK — wait for the retransmit.

            DatagramPacket second = receive(client);
            assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(second));
            assertEquals(1, TftpPacket.getBlockNumber(second));

            sendAck(client, 1, second.getAddress(), second.getPort());
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

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

                assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(pkt));
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
}
