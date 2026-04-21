package tftp.tcp.server;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Simplified TFTP-over-TCP Server.
 *
 * Usage: java tftp.tcp.server.TftpTcpServer [port]
 *   port  – TCP port to listen on (default 6970)
 *
 * Because TCP provides reliable, ordered delivery, this implementation omits
 * per-block acknowledgements and retransmissions. Each accepted connection is
 * dispatched to a {@link TftpTcpHandler} thread, enabling simultaneous transfers.
 *
 * Supported operations: RRQ (file download) and WRQ (file upload).
 * Error reported: Error code 1 (File not found) on RRQ for a missing file.
 */
public class TftpTcpServer {

    public static void main(String[] args) throws Exception {
        int port = 6970;
        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }

        ServerHandle handle = start(port, Paths.get("."));
        System.out.println("TFTP TCP Server listening on port " + handle.port());
        System.out.println("Serving directory : " + handle.baseDir().toAbsolutePath().normalize());
        System.out.println("Press Ctrl+C to stop.");
        Thread.currentThread().join();
    }

    /**
     * Starts the server bound to {@code port} (use 0 for an OS-chosen ephemeral port).
     * Returns immediately; the accept loop runs on a background daemon thread.
     * Files are served relative to {@code baseDir}.
     */
    public static ServerHandle start(int port, Path baseDir) throws IOException {
        ServerSocket serverSocket = new ServerSocket(port);
        int boundPort = serverSocket.getLocalPort();
        ExecutorService pool = Executors.newCachedThreadPool();

        Thread accept = new Thread(() -> acceptLoop(serverSocket, pool, baseDir),
                                   "tftp-tcp-accept-" + boundPort);
        accept.setDaemon(true);
        accept.start();

        return new ServerHandle(boundPort, baseDir, serverSocket, pool, accept);
    }

    private static void acceptLoop(ServerSocket serverSocket, ExecutorService pool,
                                   Path baseDir) {
        while (!serverSocket.isClosed()) {
            try {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from: " + clientSocket.getRemoteSocketAddress());
                pool.submit(new TftpTcpHandler(clientSocket, baseDir));
            } catch (IOException e) {
                if (serverSocket.isClosed()) return;
            }
        }
    }

    /** Handle returned by {@link #start}. Call {@link #stop()} to shut down. */
    public static final class ServerHandle implements AutoCloseable {
        private final int port;
        private final Path baseDir;
        private final ServerSocket serverSocket;
        private final ExecutorService pool;
        private final Thread acceptThread;

        private ServerHandle(int port, Path baseDir, ServerSocket serverSocket,
                             ExecutorService pool, Thread acceptThread) {
            this.port = port;
            this.baseDir = baseDir;
            this.serverSocket = serverSocket;
            this.pool = pool;
            this.acceptThread = acceptThread;
        }

        public int port() { return port; }
        public Path baseDir() { return baseDir; }

        public void stop() {
            try { serverSocket.close(); } catch (IOException ignored) {}
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
