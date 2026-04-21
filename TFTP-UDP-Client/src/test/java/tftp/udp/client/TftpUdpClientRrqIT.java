package tftp.udp.client;

import java.net.DatagramPacket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Integration tests for the UDP client RRQ path, using a scripted mock peer. */
class TftpUdpClientRrqIT {

    @TempDir
    Path clientDir;

    @Test
    void get_singleBlock_completesAndWritesFile() throws Exception {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i + 1);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runGet(mock.port(), "small.bin");

            DatagramPacket rrq = mock.awaitPacket();
            assertEquals(TftpPacket.OP_RRQ, TftpPacket.getOpcode(rrq));
            assertEquals("small.bin", TftpPacket.parseRequest(rrq)[0]);

            mock.reply(TftpPacket.buildData(1, body, 0, body.length));

            DatagramPacket ack = mock.awaitPacket();
            assertEquals(TftpPacket.OP_ACK, TftpPacket.getOpcode(ack));
            assertEquals(1, TftpPacket.getBlockNumber(ack));

            assertEquals(TftpUdpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        }

        assertArrayEquals(body, Files.readAllBytes(clientDir.resolve("small.bin")));
    }

    @Test
    void get_multipleBlocks_writesConcatenatedPayload() throws Exception {
        byte[] block1 = new byte[TftpPacket.BLOCK_SIZE];
        byte[] block2 = new byte[TftpPacket.BLOCK_SIZE];
        byte[] block3 = new byte[100];
        for (int i = 0; i < block1.length; i++) block1[i] = (byte) i;
        for (int i = 0; i < block2.length; i++) block2[i] = (byte) (i + 37);
        for (int i = 0; i < block3.length; i++) block3[i] = (byte) (i + 99);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runGet(mock.port(), "multi.bin");

            mock.awaitPacket();

            mock.reply(TftpPacket.buildData(1, block1, 0, block1.length));
            assertEquals(1, TftpPacket.getBlockNumber(mock.awaitPacket()));

            mock.reply(TftpPacket.buildData(2, block2, 0, block2.length));
            assertEquals(2, TftpPacket.getBlockNumber(mock.awaitPacket()));

            mock.reply(TftpPacket.buildData(3, block3, 0, block3.length));
            assertEquals(3, TftpPacket.getBlockNumber(mock.awaitPacket()));

            assertEquals(TftpUdpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        }

        byte[] expected = new byte[block1.length + block2.length + block3.length];
        System.arraycopy(block1, 0, expected, 0, block1.length);
        System.arraycopy(block2, 0, expected, block1.length, block2.length);
        System.arraycopy(block3, 0, expected, block1.length + block2.length, block3.length);
        assertArrayEquals(expected, Files.readAllBytes(clientDir.resolve("multi.bin")));
    }

    @Test
    void get_fileNotFoundError_clientReportsAndReturnsNonZero() throws Exception {
        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runGet(mock.port(), "nope.bin");

            mock.awaitPacket();
            mock.reply(TftpPacket.buildError(TftpPacket.ERR_FILE_NOT_FOUND, "File not found"));

            assertEquals(TftpUdpClient.RC_SERVER_ERROR, rc.get(5, TimeUnit.SECONDS));
        }
        assertEquals(false, Files.exists(clientDir.resolve("nope.bin")));
    }

    @Test
    void get_droppedFirstData_triggersRrqRetransmit() throws Exception {
        byte[] body = {1, 2, 3};

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runGet(mock.port(), "retr.bin");

            // Ignore the first RRQ — the client must retry after TIMEOUT_MS.
            DatagramPacket rrq1 = mock.awaitPacket();
            assertEquals(TftpPacket.OP_RRQ, TftpPacket.getOpcode(rrq1));
            DatagramPacket rrq2 = mock.awaitPacket();
            assertEquals(TftpPacket.OP_RRQ, TftpPacket.getOpcode(rrq2));

            mock.reply(TftpPacket.buildData(1, body, 0, body.length));
            assertEquals(1, TftpPacket.getBlockNumber(mock.awaitPacket()));

            assertEquals(TftpUdpClient.RC_OK, rc.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void get_finalBlockDetection() throws Exception {
        byte[] body = new byte[100];

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runGet(mock.port(), "end.bin");

            mock.awaitPacket();
            mock.reply(TftpPacket.buildData(1, body, 0, body.length));
            assertEquals(1, TftpPacket.getBlockNumber(mock.awaitPacket()));

            // After ACKing the final block the client should not send anything else.
            assertNull(mock.tryAwaitPacket(3500));

            assertEquals(TftpUdpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void get_clientUsesEphemeralSourceTid() throws Exception {
        byte[] body = new byte[10];

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runGet(mock.port(), "tid.bin");

            DatagramPacket rrq = mock.awaitPacket();
            assertNotEquals(mock.port(), rrq.getPort(),
                            "client must send from an ephemeral source port");

            mock.reply(TftpPacket.buildData(1, body, 0, body.length));
            mock.awaitPacket();
            assertEquals(TftpUdpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        }
    }

    // ------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------

    private Future<Integer> runGet(int mockPort, String filename) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Callable<Integer> task = () -> {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            return TftpUdpClient.doGet(addr, mockPort, filename, clientDir);
        };
        Future<Integer> future = exec.submit(task);
        exec.shutdown();
        return future;
    }
}
