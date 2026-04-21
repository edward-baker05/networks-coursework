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
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Client-side WRQ integration tests. Drives {@link TftpUdpClient#doPut} against
 * a scripted {@link UdpMockPeer} and inspects the wire exchange plus the
 * client's return code.
 */
class TftpUdpClientWrqIT {

    @TempDir
    Path clientDir;

    @Test
    void put_smallFile_sendsWrqThenDataAndFinishesAfterAck() throws Exception {
        byte[] body = new byte[100];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        Files.write(clientDir.resolve("small.bin"), body);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runPut(mock.port(), "small.bin");

            DatagramPacket wrq = mock.awaitPacket();
            assertEquals(TftpPacket.OP_WRQ, TftpPacket.getOpcode(wrq));
            assertEquals("small.bin", TftpPacket.parseRequest(wrq)[0]);

            mock.reply(TftpPacket.buildAck(0));

            DatagramPacket data1 = mock.awaitPacket();
            assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(data1));
            assertEquals(1, TftpPacket.getBlockNumber(data1));
            assertArrayEquals(body, TftpPacket.getData(data1));

            mock.reply(TftpPacket.buildAck(1));

            assertEquals(TftpUdpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void put_exactMultipleOf512_sendsEmptyFinalBlock() throws Exception {
        byte[] body = new byte[1024];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        Files.write(clientDir.resolve("x2.bin"), body);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runPut(mock.port(), "x2.bin");

            mock.awaitPacket();                                  // WRQ
            mock.reply(TftpPacket.buildAck(0));

            DatagramPacket b1 = mock.awaitPacket();
            assertEquals(1, TftpPacket.getBlockNumber(b1));
            assertEquals(TftpPacket.BLOCK_SIZE, TftpPacket.getData(b1).length);
            mock.reply(TftpPacket.buildAck(1));

            DatagramPacket b2 = mock.awaitPacket();
            assertEquals(2, TftpPacket.getBlockNumber(b2));
            assertEquals(TftpPacket.BLOCK_SIZE, TftpPacket.getData(b2).length);
            mock.reply(TftpPacket.buildAck(2));

            DatagramPacket b3 = mock.awaitPacket();
            assertEquals(3, TftpPacket.getBlockNumber(b3));
            assertEquals(0, TftpPacket.getData(b3).length);
            mock.reply(TftpPacket.buildAck(3));

            assertEquals(TftpUdpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void put_localFileMissing_returnsNonZeroWithoutSendingWrq() throws Exception {
        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runPut(mock.port(), "missing.bin");

            // No WRQ should arrive
            assertNull(mock.tryAwaitPacket(1000));

            assertEquals(TftpUdpClient.RC_LOCAL_NOT_FOUND, rc.get(3, TimeUnit.SECONDS));
        }
    }

    @Test
    void put_serverError_reportsAndReturnsNonZero() throws Exception {
        Files.write(clientDir.resolve("access.bin"), new byte[10]);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runPut(mock.port(), "access.bin");

            mock.awaitPacket();                                  // WRQ
            mock.reply(TftpPacket.buildError(TftpPacket.ERR_ACCESS_VIOLATION,
                                             "Access violation"));

            assertEquals(TftpUdpClient.RC_SERVER_ERROR, rc.get(5, TimeUnit.SECONDS));
        }
    }

    @Test
    void put_droppedAck0_triggersWrqRetransmit() throws Exception {
        Files.write(clientDir.resolve("retwrq.bin"), new byte[3]);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runPut(mock.port(), "retwrq.bin");

            DatagramPacket wrq1 = mock.awaitPacket();
            assertEquals(TftpPacket.OP_WRQ, TftpPacket.getOpcode(wrq1));

            DatagramPacket wrq2 = mock.awaitPacket();
            assertEquals(TftpPacket.OP_WRQ, TftpPacket.getOpcode(wrq2));

            mock.reply(TftpPacket.buildAck(0));
            DatagramPacket data1 = mock.awaitPacket();
            assertEquals(1, TftpPacket.getBlockNumber(data1));

            mock.reply(TftpPacket.buildAck(1));
            assertEquals(TftpUdpClient.RC_OK, rc.get(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void put_droppedAckN_triggersDataRetransmit() throws Exception {
        byte[] body = new byte[TftpPacket.BLOCK_SIZE + 5];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        Files.write(clientDir.resolve("ackN.bin"), body);

        try (UdpMockPeer mock = new UdpMockPeer()) {
            Future<Integer> rc = runPut(mock.port(), "ackN.bin");

            mock.awaitPacket();                                  // WRQ
            mock.reply(TftpPacket.buildAck(0));

            DatagramPacket d1a = mock.awaitPacket();
            assertEquals(1, TftpPacket.getBlockNumber(d1a));
            // DROP: don't ACK. Wait for retransmit.
            DatagramPacket d1b = mock.awaitPacket();
            assertEquals(1, TftpPacket.getBlockNumber(d1b));

            mock.reply(TftpPacket.buildAck(1));
            DatagramPacket d2 = mock.awaitPacket();
            assertEquals(2, TftpPacket.getBlockNumber(d2));
            mock.reply(TftpPacket.buildAck(2));

            assertEquals(TftpUdpClient.RC_OK, rc.get(15, TimeUnit.SECONDS));
        }
    }

    // ------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------

    private Future<Integer> runPut(int mockPort, String filename) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Callable<Integer> task = () -> {
            InetAddress addr = InetAddress.getByName("127.0.0.1");
            return TftpUdpClient.doPut(addr, mockPort, filename, clientDir);
        };
        Future<Integer> future = exec.submit(task);
        exec.shutdown();
        return future;
    }
}
