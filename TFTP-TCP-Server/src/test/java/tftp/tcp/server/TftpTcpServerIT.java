package tftp.tcp.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** End-to-end integration tests for the TFTP-TCP server. */
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
            TftpTcpProtocol.readNullTermString(in);
        }
    }

    @Test
    void wrq_acceptsDataFrameAndWritesFile() throws Exception {
        byte[] body = new byte[512];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i + 1);

        try (Socket sock = new Socket(localhost, server.port());
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            TftpTcpProtocol.writeWRQ(out, "upload.bin");
            TftpTcpProtocol.writeData(out, body);

            try {
                int opcode = in.readShort() & 0xFFFF;
                if (opcode == TftpTcpProtocol.OP_ERROR) {
                    int code = TftpTcpProtocol.readErrorCode(in);
                    String msg = TftpTcpProtocol.readNullTermString(in);
                    throw new AssertionError("Unexpected server error " + code + ": " + msg);
                }
            } catch (EOFException ignored) {
                // normal success path
            }
        }

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

    @Test
    void simultaneousTransfers_allSucceed() throws Exception {
        Random rng = new Random(1);
        int n = 4;
        byte[][] bodies = new byte[n][];
        for (int i = 0; i < n; i++) {
            bodies[i] = new byte[500 + rng.nextInt(1000)];
            rng.nextBytes(bodies[i]);
            Files.write(serverDir.resolve("r" + i + ".bin"), bodies[i]);
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        List<Future<?>> futures = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int idx = i;
            futures.add(pool.submit(() -> {
                try (Socket sock = new Socket(localhost, server.port());
                     DataInputStream  in  = new DataInputStream(sock.getInputStream());
                     DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {
                    TftpTcpProtocol.writeRRQ(out, "r" + idx + ".bin");
                    in.readShort();
                    byte[] got = TftpTcpProtocol.readData(in);
                    assertArrayEquals(bodies[idx], got);
                }
                return null;
            }));
        }
        pool.shutdown();
        for (Future<?> f : futures) f.get(15, TimeUnit.SECONDS);
    }
}
