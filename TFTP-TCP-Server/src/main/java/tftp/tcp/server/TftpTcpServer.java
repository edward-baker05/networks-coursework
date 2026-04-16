package tftp.tcp.server;

import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

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

        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("TFTP TCP Server listening on port " + port);
            System.out.println("Serving directory : " + System.getProperty("user.dir"));
            System.out.println("Press Ctrl+C to stop.");

            while (!Thread.currentThread().isInterrupted()) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Connection from: " + clientSocket.getRemoteSocketAddress());
                pool.submit(new TftpTcpHandler(clientSocket));
            }
        } finally {
            pool.shutdown();
        }
    }
}
