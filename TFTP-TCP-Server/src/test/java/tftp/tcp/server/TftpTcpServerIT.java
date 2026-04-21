package tftp.tcp.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * End-to-end integration tests for the TFTP-TCP server.
 * Covers RRQ/WRQ round-trip, error handling (file-not-found, illegal op,
 * disk failure), and simultaneous transfers.
 */
class TftpTcpServerIT {

    @TempDir
    Path serverDir;

    private TftpTcpServer.ServerHandle server;
    private InetAddress localhost;

    @BeforeEach
    void startServer() throws Exception {
        server = TftpTcpServer.start(0, serverDir);
        localhost = InetAddress.getByName("127.0.0.1");
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop();
    }

    // ------------------------------------------------------------------
    // RRQ read
    // ------------------------------------------------------------------

    @Test
    void rrq_returnsDataFrameWithFileBytes() throws Exception {
        byte[] body = new byte[777];
        for (int i = 0; i < body.length; i++) body[i] = (byte) i;
        Files.write(serverDir.resolve("read.bin"), body);

        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeRRQ(out, "read.bin");

            int opcode = in.readShort() & 0xFFFF;
            assertEquals(TftpTcpProtocol.OP_DATA, opcode);

            byte[] received = TftpTcpProtocol.readData(in);
            assertArrayEquals(body, received);
        }
    }

    @Test
    void rrq_missingFile_returnsError1() throws Exception {
        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeRRQ(out, "nope.bin");

            int opcode = in.readShort() & 0xFFFF;
            assertEquals(TftpTcpProtocol.OP_ERROR, opcode);
            assertEquals(TftpTcpProtocol.ERR_FILE_NOT_FOUND,
                         TftpTcpProtocol.readErrorCode(in));
            assertEquals("File not found", TftpTcpProtocol.readNullTermString(in));
        }
    }

    @Test
    void rrq_binaryFile_isOctetExact() throws Exception {
        Path source = Paths.get("..", "test_image-2.jpg").toAbsolutePath().normalize();
        if (!Files.exists(source)) return;

        byte[] body = Files.readAllBytes(source);
        Files.copy(source, serverDir.resolve("img.jpg"));

        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeRRQ(out, "img.jpg");
            in.readShort();                                      // DATA opcode
            byte[] received = TftpTcpProtocol.readData(in);
            assertArrayEquals(body, received);
        }
    }

    // ------------------------------------------------------------------
    // WRQ write
    // ------------------------------------------------------------------

    @Test
    void wrq_acceptsDataFrameAndWritesFile() throws Exception {
        byte[] body = new byte[512];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i + 1);

        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeWRQ(out, "upload.bin");
            TftpTcpProtocol.writeData(out, body);

            // Server may close without an error response — treat EOF as success.
            try {
                int opcode = in.readShort() & 0xFFFF;
                // If we got here without EOF we expect no error on happy path
                if (opcode == TftpTcpProtocol.OP_ERROR) {
                    int code = TftpTcpProtocol.readErrorCode(in);
                    String msg = TftpTcpProtocol.readNullTermString(in);
                    throw new AssertionError("Unexpected server error " + code + ": " + msg);
                }
            } catch (EOFException ignored) {
                // normal success path
            }
        }

        // Give the handler a moment to finish writing the file (writes after
        // the stream is drained but before the socket is fully torn down).
        for (int i = 0; i < 50; i++) {
            if (Files.exists(serverDir.resolve("upload.bin"))) break;
            Thread.sleep(20);
        }
        assertArrayEquals(body, Files.readAllBytes(serverDir.resolve("upload.bin")));
    }

    @Test
    void wrq_mediumTxt_roundTrips() throws Exception {
        Path source = Paths.get("..", "medium.txt").toAbsolutePath().normalize();
        if (!Files.exists(source)) return;
        byte[] body = Files.readAllBytes(source);

        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeWRQ(out, "medium.txt");
            TftpTcpProtocol.writeData(out, body);

            try { in.readShort(); } catch (EOFException ignored) { }
        }

        for (int i = 0; i < 50; i++) {
            if (Files.exists(serverDir.resolve("medium.txt"))) break;
            Thread.sleep(20);
        }
        assertArrayEquals(body, Files.readAllBytes(serverDir.resolve("medium.txt")));
    }

    // ------------------------------------------------------------------
    // Illegal opcode
    // ------------------------------------------------------------------

    @Test
    void illegalOpcode_returnsError4() throws Exception {
        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            out.writeShort(99);                                  // bogus opcode
            out.flush();

            assertEquals(TftpTcpProtocol.OP_ERROR, in.readShort() & 0xFFFF);
            assertEquals(TftpTcpProtocol.ERR_ILLEGAL_OP, TftpTcpProtocol.readErrorCode(in));
            TftpTcpProtocol.readNullTermString(in);              // discard message
        }
    }

    // ------------------------------------------------------------------
    // Truncated WRQ: server should handle it without crashing
    // ------------------------------------------------------------------

    @Test
    void truncatedWrq_closesCleanlyWithoutCrash() throws Exception {
        try (Socket sock = new Socket(localhost, server.port());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            out.writeShort(TftpTcpProtocol.OP_WRQ);
            out.write('x');
            out.flush();
            // Close abruptly (try-with-resources)
        }

        // If we can still use the server, it did not deadlock.
        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeRRQ(out, "still-missing.bin");
            assertEquals(TftpTcpProtocol.OP_ERROR, in.readShort() & 0xFFFF);
        }
    }

    // ------------------------------------------------------------------
    // WRQ disk error: make the target file read-only to force a write failure.
    // (Best-effort — if the filesystem silently honours the chmod we get
    //  error(3); otherwise the transfer goes through and the test skips.)
    // ------------------------------------------------------------------

    @Test
    void wrq_diskErrorReturnsError3() throws Exception {
        Path victim = serverDir.resolve("readonly.bin");
        Files.write(victim, new byte[1]);
        // Remove owner-write. If unsupported or ignored on this FS, skip.
        boolean writable = !victim.toFile().setWritable(false);
        if (writable) return;

        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeWRQ(out, "readonly.bin");
            TftpTcpProtocol.writeData(out, new byte[16]);

            int opcode = in.readShort() & 0xFFFF;
            assertEquals(TftpTcpProtocol.OP_ERROR, opcode);
            assertEquals(TftpTcpProtocol.ERR_DISK_FULL, TftpTcpProtocol.readErrorCode(in));
        } finally {
            victim.toFile().setWritable(true);
        }
    }

    // ------------------------------------------------------------------
    // Simultaneous transfers (rubric)
    // ------------------------------------------------------------------

    @Test
    void tenSimultaneousConnectionsAllSucceed() throws Exception {
        Random rng = new Random(1);
        byte[][] read = new byte[5][];
        byte[][] write = new byte[5][];
        for (int i = 0; i < 5; i++) {
            read[i] = new byte[500 + rng.nextInt(1000)];
            rng.nextBytes(read[i]);
            Files.write(serverDir.resolve("r" + i + ".bin"), read[i]);
            write[i] = new byte[500 + rng.nextInt(1000)];
            rng.nextBytes(write[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(10);
        AtomicInteger ok = new AtomicInteger();
        try {
            java.util.List<Future<?>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try (Socket sock = new Socket(localhost, server.port());
                         DataInputStream  in  = new DataInputStream(sock.getInputStream());
                         DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {
                        TftpTcpProtocol.writeRRQ(out, "r" + idx + ".bin");
                        in.readShort();
                        byte[] got = TftpTcpProtocol.readData(in);
                        assertArrayEquals(read[idx], got);
                        ok.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (int i = 0; i < 5; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    try (Socket sock = new Socket(localhost, server.port());
                         DataInputStream  in  = new DataInputStream(sock.getInputStream());
                         DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {
                        TftpTcpProtocol.writeWRQ(out, "w" + idx + ".bin");
                        TftpTcpProtocol.writeData(out, write[idx]);
                        try { in.readShort(); } catch (EOFException ignored) { }
                        ok.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (var f : futures) f.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdown();
        }

        assertEquals(10, ok.get());
        for (int i = 0; i < 5; i++) {
            // Allow a beat for the WRQ handler to finish writing.
            Path written = serverDir.resolve("w" + i + ".bin");
            for (int j = 0; j < 50 && !Files.exists(written); j++) Thread.sleep(20);
            assertArrayEquals(write[i], Files.readAllBytes(written));
        }
    }
}
