package tftp.tcp.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Callable;
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
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Integration tests for the TCP client. Drives {@link TftpTcpClient#doGet}
 * and {@link TftpTcpClient#doPut} against a mock {@link ServerSocket} that
 * scripts the expected wire exchange.
 */
class TftpTcpClientIT {

    @TempDir
    Path clientDir;

    private ServerSocket mock;

    @BeforeEach
    void startMock() throws Exception {
        mock = new ServerSocket(0);
    }

    @AfterEach
    void stopMock() throws Exception {
        if (mock != null) mock.close();
    }

    // ------------------------------------------------------------------
    // RRQ: get
    // ------------------------------------------------------------------

    @Test
    void get_receivesDataAndWritesFile() throws Exception {
        byte[] body = new byte[1024];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i & 0xFF);

        Future<Integer> rc = runGet("grab.bin");

        try (Socket sock = mock.accept();
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            assertEquals(TftpTcpProtocol.OP_RRQ, in.readShort() & 0xFFFF);
            assertEquals("grab.bin", TftpTcpProtocol.readRequest(in)[0]);

            TftpTcpProtocol.writeData(out, body);
        }

        assertEquals(TftpTcpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
        assertArrayEquals(body, Files.readAllBytes(clientDir.resolve("grab.bin")));
    }

    @Test
    void get_errorFrame_reportsAndReturnsNonZero() throws Exception {
        Future<Integer> rc = runGet("nope.bin");

        try (Socket sock = mock.accept();
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            assertEquals(TftpTcpProtocol.OP_RRQ, in.readShort() & 0xFFFF);
            TftpTcpProtocol.readRequest(in);

            TftpTcpProtocol.writeError(out, TftpTcpProtocol.ERR_FILE_NOT_FOUND,
                                       "File not found");
        }

        assertEquals(TftpTcpClient.RC_SERVER_ERROR, rc.get(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(clientDir.resolve("nope.bin")));
    }

    @Test
    void get_unexpectedOpcode_returnsNonZero() throws Exception {
        Future<Integer> rc = runGet("weird.bin");

        try (Socket sock = mock.accept();
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            assertEquals(TftpTcpProtocol.OP_RRQ, in.readShort() & 0xFFFF);
            TftpTcpProtocol.readRequest(in);

            out.writeShort(99);
            out.flush();
        }

        int result = rc.get(5, TimeUnit.SECONDS);
        assertEquals(TftpTcpClient.RC_PROTOCOL_ERROR, result);
    }

    // ------------------------------------------------------------------
    // WRQ: put
    // ------------------------------------------------------------------

    @Test
    void put_sendsWrqPlusDataAndCompletes() throws Exception {
        byte[] body = new byte[2048];
        for (int i = 0; i < body.length; i++) body[i] = (byte) (i ^ 0x5A);
        Files.write(clientDir.resolve("upload.bin"), body);

        Future<Integer> rc = runPut("upload.bin");

        try (Socket sock = mock.accept();
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            assertEquals(TftpTcpProtocol.OP_WRQ, in.readShort() & 0xFFFF);
            assertEquals("upload.bin", TftpTcpProtocol.readRequest(in)[0]);

            assertEquals(TftpTcpProtocol.OP_DATA, in.readShort() & 0xFFFF);
            byte[] received = TftpTcpProtocol.readData(in);
            assertArrayEquals(body, received);
            // Close without writing anything back — success path (EOF).
        }

        assertEquals(TftpTcpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
    }

    @Test
    void put_localFileMissing_returnsNonZeroWithoutConnecting() throws Exception {
        Future<Integer> rc = runPut("missing.bin");
        assertEquals(TftpTcpClient.RC_LOCAL_NOT_FOUND, rc.get(3, TimeUnit.SECONDS));
        // No client ever connected; the ServerSocket remains available.
    }

    @Test
    void put_serverErrorAfterWrite_reportsAndReturnsNonZero() throws Exception {
        byte[] body = new byte[4];
        Files.write(clientDir.resolve("reject.bin"), body);

        Future<Integer> rc = runPut("reject.bin");

        try (Socket sock = mock.accept();
             DataInputStream  in  = new DataInputStream(sock.getInputStream());
             DataOutputStream out = new DataOutputStream(sock.getOutputStream())) {

            in.readShort();                                      // WRQ opcode
            TftpTcpProtocol.readRequest(in);
            in.readShort();                                      // DATA opcode
            TftpTcpProtocol.readData(in);

            TftpTcpProtocol.writeError(out, TftpTcpProtocol.ERR_DISK_FULL, "Disk full");
        }

        assertEquals(TftpTcpClient.RC_SERVER_ERROR, rc.get(5, TimeUnit.SECONDS));
    }

    @Test
    void put_serverClosesWithoutError_returnsZero() throws Exception {
        byte[] body = new byte[10];
        Files.write(clientDir.resolve("eof.bin"), body);

        Future<Integer> rc = runPut("eof.bin");

        try (Socket sock = mock.accept();
             DataInputStream  in  = new DataInputStream(sock.getInputStream())) {

            in.readShort();
            TftpTcpProtocol.readRequest(in);
            in.readShort();
            TftpTcpProtocol.readData(in);
            // Silent close → client should see EOF → success
        }

        assertEquals(TftpTcpClient.RC_OK, rc.get(5, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------
    // Infrastructure
    // ------------------------------------------------------------------

    private Future<Integer> runGet(String filename) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Callable<Integer> task = () -> TftpTcpClient.doGet(
                "127.0.0.1", mock.getLocalPort(), filename, clientDir);
        Future<Integer> f = exec.submit(task);
        exec.shutdown();
        return f;
    }

    private Future<Integer> runPut(String filename) {
        ExecutorService exec = Executors.newSingleThreadExecutor();
        Callable<Integer> task = () -> TftpTcpClient.doPut(
                "127.0.0.1", mock.getLocalPort(), filename, clientDir);
        Future<Integer> f = exec.submit(task);
        exec.shutdown();
        return f;
    }
}
