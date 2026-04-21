package tftp.udp.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** Tests the server's handling of an initial RRQ/WRQ arriving at the welcome port. */
class TftpUdpServerInitiationIT {

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
    void rrqBytesArriveAtWelcomePortInCorrectFormat() throws Exception {
        Files.write(serverDir.resolve("init.bin"), new byte[20]);

        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] rrq = TftpPacket.buildRRQ("init.bin");
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            client.receive(response);

            assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(response));
            assertEquals(1, TftpPacket.getBlockNumber(response));

            // ACK(1) closes the session cleanly.
            byte[] ack = TftpPacket.buildAck(1);
            client.send(new DatagramPacket(ack, ack.length,
                                           response.getAddress(), response.getPort()));
        }
    }

    @Test
    void wrqBytesArriveAtWelcomePortInCorrectFormat() throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(3000);

            byte[] wrq = TftpPacket.buildWRQ("init-w.bin");
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
            DatagramPacket response = new DatagramPacket(buf, buf.length);
            client.receive(response);

            assertEquals(TftpPacket.OP_ACK, TftpPacket.getOpcode(response));
            assertEquals(0, TftpPacket.getBlockNumber(response));

            // Finalise with an empty DATA(1) so the server closes cleanly.
            byte[] end = TftpPacket.buildData(1, new byte[0], 0, 0);
            client.send(new DatagramPacket(end, end.length,
                                           response.getAddress(), response.getPort()));
            client.receive(new DatagramPacket(buf, buf.length));
        }
    }
}
