package tftp.udp.server;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * TFTP UDP Server (RFC 1350, octet mode).
 *
 * Usage: java tftp.udp.server.TftpUdpServer [port]
 *
 * Listens on the given port (default 6969) for RRQ and WRQ requests.
 * Each request is handled on a new thread with its own ephemeral socket,
 * allowing multiple simultaneous transfers.
 */
public class TftpUdpServer {

    public static void main(String[] args) throws Exception {
        int port = 6969;
        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }

        ServerHandle handle = start(port, Paths.get("."));
        System.out.println("TFTP UDP Server listening on port " + handle.port());
        System.out.println("Serving directory : " + handle.baseDir().toAbsolutePath().normalize());
        System.out.println("Press Ctrl+C to stop.");
        // Block the main thread; the accept loop runs on a daemon thread.
        Thread.currentThread().join();
    }

    /**
     * Starts the server bound to {@code port} (use 0 for an OS-chosen ephemeral port).
     * Returns immediately; the accept loop runs on a background daemon thread.
     * Files are served relative to {@code baseDir}.
     */
    public static ServerHandle start(int port, Path baseDir) throws IOException {
        DatagramSocket welcomeSocket = new DatagramSocket(port);
        int boundPort = welcomeSocket.getLocalPort();
        ExecutorService pool = Executors.newCachedThreadPool();

        Thread accept = new Thread(() -> acceptLoop(welcomeSocket, pool, baseDir),
                                   "tftp-udp-accept-" + boundPort);
        accept.setDaemon(true);
        accept.start();

        return new ServerHandle(boundPort, baseDir, welcomeSocket, pool, accept);
    }

    private static void acceptLoop(DatagramSocket welcomeSocket, ExecutorService pool,
                                   Path baseDir) {
        byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
        while (!welcomeSocket.isClosed()) {
            DatagramPacket request = new DatagramPacket(buf, buf.length);
            try {
                welcomeSocket.receive(request);
            } catch (IOException e) {
                if (welcomeSocket.isClosed()) return;
                continue;
            }

            int opcode = TftpPacket.getOpcode(request);
            if (opcode != TftpPacket.OP_RRQ && opcode != TftpPacket.OP_WRQ) {
                continue;
            }

            byte[] reqCopy = new byte[request.getLength()];
            System.arraycopy(request.getData(), request.getOffset(),
                             reqCopy, 0, request.getLength());
            InetAddress clientAddr = request.getAddress();
            int clientPort = request.getPort();

            pool.submit(new TftpTransferHandler(opcode, reqCopy, clientAddr, clientPort, baseDir));
        }
    }

    /** Handle returned by {@link #start}. Call {@link #stop()} to shut down. */
    public static final class ServerHandle implements AutoCloseable {
        private final int port;
        private final Path baseDir;
        private final DatagramSocket welcomeSocket;
        private final ExecutorService pool;
        private final Thread acceptThread;

        private ServerHandle(int port, Path baseDir, DatagramSocket welcomeSocket,
                             ExecutorService pool, Thread acceptThread) {
            this.port = port;
            this.baseDir = baseDir;
            this.welcomeSocket = welcomeSocket;
            this.pool = pool;
            this.acceptThread = acceptThread;
        }

        public int port() { return port; }
        public Path baseDir() { return baseDir; }

        public void stop() {
            welcomeSocket.close();
            pool.shutdownNow();
            try {
                pool.awaitTermination(2, TimeUnit.SECONDS);
                acceptThread.join(2000);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }

        @Override public void close() { stop(); }
    }
}
