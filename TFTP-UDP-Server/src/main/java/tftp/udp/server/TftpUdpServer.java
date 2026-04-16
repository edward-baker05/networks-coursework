package tftp.udp.server;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * TFTP UDP Server (RFC 1350, octet mode).
 *
 * Usage: java tftp.udp.server.TftpUdpServer [port]
 *   port  – UDP port to bind (default 6969)
 *
 * The server operates (reads and writes files) in the current working directory.
 * Each incoming RRQ/WRQ spawns a dedicated handler thread that opens its own
 * ephemeral socket (the server's Transfer Identifier, TID) and drives the
 * transfer independently, enabling simultaneous file transfers.
 */
public class TftpUdpServer {

    public static void main(String[] args) {
        int port = 6969;
        if (args.length >= 1) {
            try {
                port = Integer.parseInt(args[0]);
            } catch (NumberFormatException e) {
                System.err.println("Invalid port: " + args[0]);
                System.exit(1);
            }
        }

        ExecutorService pool = Executors.newCachedThreadPool();

        try (DatagramSocket welcomeSocket = new DatagramSocket(port)) {
            System.out.println("TFTP UDP Server listening on port " + port);
            System.out.println("Serving directory : " + System.getProperty("user.dir"));
            System.out.println("Press Ctrl+C to stop.");

            byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];

            while (!Thread.currentThread().isInterrupted()) {
                DatagramPacket request = new DatagramPacket(buf, buf.length);
                welcomeSocket.receive(request);

                int opcode = TftpPacket.getOpcode(request);
                if (opcode != TftpPacket.OP_RRQ && opcode != TftpPacket.OP_WRQ) {
                    sendError(welcomeSocket, request.getAddress(), request.getPort(),
                              TftpPacket.ERR_ILLEGAL_OP, "Expected RRQ or WRQ");
                    continue;
                }

                // Copy packet bytes before the receive buffer is reused
                byte[] reqCopy = new byte[request.getLength()];
                System.arraycopy(request.getData(), request.getOffset(),
                                 reqCopy, 0, request.getLength());

                InetAddress clientAddr = request.getAddress();
                int clientPort = request.getPort();

                pool.submit(new TftpTransferHandler(opcode, reqCopy, clientAddr, clientPort));
            }
        } catch (Exception e) {
            System.err.println("Server error: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    private static void sendError(DatagramSocket socket, InetAddress addr, int port,
                                  int code, String msg) {
        try {
            byte[] err = TftpPacket.buildError(code, msg);
            socket.send(new DatagramPacket(err, err.length, addr, port));
        } catch (Exception ignored) {}
    }
}
