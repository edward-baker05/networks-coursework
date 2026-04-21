package tftp.udp.server;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simultaneous transfers (rubric: "simultaneous transfers").
 * Demonstrates that the welcome-port / ephemeral-handler design supports
 * many parallel RRQ/WRQ sessions without cross-talk or data corruption.
 */
class TftpUdpServerConcurrentIT {

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
    void tenSimultaneousRrqsAllSucceed() throws Exception {
        int n = 10;
        byte[][] originals = new byte[n][];
        Random rng = new Random(0xC0FFEE);
        for (int i = 0; i < n; i++) {
            originals[i] = new byte[2000 + rng.nextInt(3000)];
            rng.nextBytes(originals[i]);
            Files.write(serverDir.resolve("file-" + i + ".bin"), originals[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        int[] transferPorts = new int[n];
        byte[][] received = new byte[n][];

        try {
            java.util.List<java.util.concurrent.Future<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    byte[] body = runRrq("file-" + idx + ".bin", transferPorts, idx);
                    received[idx] = body;
                    return null;
                }));
            }
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        for (int i = 0; i < n; i++) {
            assertArrayEquals(originals[i], received[i], "file " + i + " corrupted");
        }

        // Every transfer must have used a unique TID (ephemeral port).
        java.util.Set<Integer> distinct = new java.util.HashSet<>();
        for (int p : transferPorts) distinct.add(p);
        assertEquals(n, distinct.size(), "server did not use a unique TID per transfer");
    }

    @Test
    void fiveRrqsFiveWrqsInterleaved() throws Exception {
        // 5 files on disk ready to be read; 5 uploads target brand-new names.
        byte[][] reads = new byte[5][];
        Random rng = new Random(42);
        for (int i = 0; i < 5; i++) {
            reads[i] = new byte[1500 + rng.nextInt(2000)];
            rng.nextBytes(reads[i]);
            Files.write(serverDir.resolve("r-" + i + ".bin"), reads[i]);
        }

        byte[][] writes = new byte[5][];
        for (int i = 0; i < 5; i++) {
            writes[i] = new byte[1800 + rng.nextInt(2500)];
            rng.nextBytes(writes[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger completed = new AtomicInteger();
        try {
            java.util.List<java.util.concurrent.Future<Void>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    byte[] result = runRrq("r-" + idx + ".bin", null, 0);
                    assertArrayEquals(reads[idx], result);
                    completed.incrementAndGet();
                    return null;
                }));
            }
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    runWrq("w-" + idx + ".bin", writes[idx]);
                    completed.incrementAndGet();
                    return null;
                }));
            }
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertEquals(10, completed.get());
        for (int i = 0; i < 5; i++) {
            assertArrayEquals(writes[i], Files.readAllBytes(serverDir.resolve("w-" + i + ".bin")));
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private byte[] runRrq(String filename, int[] ports, int portIndex) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(15000);

            byte[] rrq = TftpPacket.buildRRQ(filename);
            client.send(new DatagramPacket(rrq, rrq.length, localhost, server.port()));

            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            int expected = 1;
            int transferPort = -1;

            while (true) {
                DatagramPacket pkt = receive(client);
                if (transferPort < 0) {
                    transferPort = pkt.getPort();
                    if (ports != null) ports[portIndex] = transferPort;
                    assertNotEquals(server.port(), transferPort);
                }

                assertEquals(TftpPacket.OP_DATA, TftpPacket.getOpcode(pkt));
                assertEquals(expected, TftpPacket.getBlockNumber(pkt));
                byte[] payload = TftpPacket.getData(pkt);
                acc.write(payload);

                byte[] ack = TftpPacket.buildAck(expected);
                client.send(new DatagramPacket(ack, ack.length, localhost, transferPort));

                if (payload.length < TftpPacket.BLOCK_SIZE) break;
                expected++;
            }
            return acc.toByteArray();
        }
    }

    private void runWrq(String filename, byte[] body) throws Exception {
        try (DatagramSocket client = new DatagramSocket()) {
            client.setSoTimeout(15000);

            byte[] wrq = TftpPacket.buildWRQ(filename);
            client.send(new DatagramPacket(wrq, wrq.length, localhost, server.port()));

            DatagramPacket ack0 = receive(client);
            assertEquals(0, TftpPacket.getBlockNumber(ack0));
            int transferPort = ack0.getPort();
            assertNotEquals(server.port(), transferPort);

            int blockNum = 1;
            int offset = 0;
            boolean done = false;
            while (!done) {
                int len = Math.min(TftpPacket.BLOCK_SIZE, body.length - offset);
                done = (len < TftpPacket.BLOCK_SIZE);
                byte[] pkt = TftpPacket.buildData(blockNum, body, offset, len);
                client.send(new DatagramPacket(pkt, pkt.length, localhost, transferPort));

                DatagramPacket ack = receive(client);
                assertTrue(TftpPacket.getBlockNumber(ack) == blockNum);
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
}
