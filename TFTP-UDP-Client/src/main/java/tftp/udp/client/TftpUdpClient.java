package tftp.udp.client;

import java.io.ByteArrayOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * TFTP UDP Client (RFC 1350, octet mode).
 *
 * Usage:
 *   java tftp.udp.client.TftpUdpClient get <filename> [host] [port]
 *   java tftp.udp.client.TftpUdpClient put <filename> [host] [port]
 *
 * With no arguments an interactive menu is shown.
 * Defaults: host = localhost, port = 6969.
 */
public class TftpUdpClient {

    private static final int TIMEOUT_MS  = 2000;
    private static final int MAX_RETRIES = 5;

    public static final int RC_OK              = 0;
    public static final int RC_SERVER_ERROR    = 1;
    public static final int RC_TIMEOUT         = 2;
    public static final int RC_LOCAL_NOT_FOUND = 3;

    public static void main(String[] args) throws Exception {
        String operation = null;
        String filename  = null;
        String host      = "localhost";
        int    port      = 6969;

        if (args.length >= 2) {
            operation = args[0].toLowerCase();
            filename  = args[1];
            if (args.length >= 3) host = args[2];
            if (args.length >= 4) port = Integer.parseInt(args[3]);
        } else {
            Scanner sc = new Scanner(System.in);
            System.out.println("=== TFTP UDP Client ===");
            System.out.print("Server host [localhost]: ");
            String h = sc.nextLine().trim();
            if (!h.isEmpty()) host = h;
            System.out.print("Server port [6969]: ");
            String p = sc.nextLine().trim();
            if (!p.isEmpty()) port = Integer.parseInt(p);
            System.out.println("1) Get file (RRQ)");
            System.out.println("2) Put file (WRQ)");
            System.out.print("Choice: ");
            int choice = Integer.parseInt(sc.nextLine().trim());
            operation = (choice == 2) ? "put" : "get";
            System.out.print("Filename: ");
            filename = sc.nextLine().trim();
        }

        InetAddress serverAddr = InetAddress.getByName(host);
        Path baseDir = Paths.get(".");

        int rc = switch (operation) {
            case "get" -> doGet(serverAddr, port, filename, baseDir);
            case "put" -> doPut(serverAddr, port, filename, baseDir);
            default -> {
                System.err.println("Unknown operation '" + operation + "'. Use 'get' or 'put'.");
                yield 1;
            }
        };
        System.exit(rc);
    }

    // -------------------------------------------------------------------------
    // GET (RRQ): request a file from the server.
    // -------------------------------------------------------------------------

    static int doGet(InetAddress serverAddr, int serverPort,
                     String filename, Path baseDir) throws Exception {
        System.out.println("[GET] " + filename
                           + " from " + serverAddr.getHostAddress() + ":" + serverPort);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            byte[] rrq = TftpPacket.buildRRQ(filename);
            socket.send(new DatagramPacket(rrq, rrq.length, serverAddr, serverPort));

            // Server TID is captured from the first response packet.
            InetAddress serverTid     = null;
            int         serverTidPort = -1;

            ByteArrayOutputStream fileData = new ByteArrayOutputStream();
            int  expectedBlock = 1;
            byte[] lastSent    = rrq;
            InetAddress lastDest = serverAddr;
            int lastDestPort     = serverPort;
            int retries          = 0;

            while (true) {
                byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);

                try {
                    socket.receive(pkt);
                    retries = 0;
                } catch (SocketTimeoutException e) {
                    if (++retries >= MAX_RETRIES) {
                        System.err.println("[GET] Timed out after " + MAX_RETRIES + " retries.");
                        return RC_TIMEOUT;
                    }
                    socket.send(new DatagramPacket(lastSent, lastSent.length, lastDest, lastDestPort));
                    continue;
                }

                if (serverTid == null) {
                    serverTid     = pkt.getAddress();
                    serverTidPort = pkt.getPort();
                }

                int op = TftpPacket.getOpcode(pkt);

                if (op == TftpPacket.OP_ERROR) {
                    System.err.println("[GET] Server error " + TftpPacket.getErrorCode(pkt)
                                       + ": " + TftpPacket.getErrorMessage(pkt));
                    return RC_SERVER_ERROR;
                }

                if (op != TftpPacket.OP_DATA) continue;

                int blockNum = TftpPacket.getBlockNumber(pkt);
                if (blockNum != expectedBlock) continue;

                byte[] data = TftpPacket.getData(pkt);
                fileData.write(data);

                byte[] ack = TftpPacket.buildAck(blockNum);
                socket.send(new DatagramPacket(ack, ack.length, serverTid, serverTidPort));
                lastSent     = ack;
                lastDest     = serverTid;
                lastDestPort = serverTidPort;

                if (data.length < TftpPacket.BLOCK_SIZE) {
                    break;
                }

                expectedBlock++;
            }

            Files.write(baseDir.resolve(filename), fileData.toByteArray());
            System.out.println("[GET] Complete: " + filename + " (" + fileData.size() + " bytes)");
            return RC_OK;
        }
    }

    // -------------------------------------------------------------------------
    // PUT (WRQ): send a local file to the server.
    // -------------------------------------------------------------------------

    static int doPut(InetAddress serverAddr, int serverPort,
                     String filename, Path baseDir) throws Exception {
        Path filePath = baseDir.resolve(filename);
        if (!Files.exists(filePath)) {
            System.err.println("[PUT] Local file not found: " + filename);
            return RC_LOCAL_NOT_FOUND;
        }

        byte[] fileData = Files.readAllBytes(filePath);
        System.out.println("[PUT] " + filename + " (" + fileData.length + " bytes)"
                           + " to " + serverAddr.getHostAddress() + ":" + serverPort);

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            byte[] wrq = TftpPacket.buildWRQ(filename);
            socket.send(new DatagramPacket(wrq, wrq.length, serverAddr, serverPort));

            InetAddress serverTid     = null;
            int         serverTidPort = -1;

            byte[] lastSent  = wrq;
            InetAddress lastDest = serverAddr;
            int lastDestPort     = serverPort;
            int retries          = 0;

            // Wait for ACK(0) to confirm the server accepted the WRQ.
            ack0Loop:
            while (true) {
                byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);

                try {
                    socket.receive(pkt);
                    retries = 0;
                } catch (SocketTimeoutException e) {
                    if (++retries >= MAX_RETRIES) {
                        System.err.println("[PUT] Timed out waiting for ACK(0).");
                        return RC_TIMEOUT;
                    }
                    socket.send(new DatagramPacket(lastSent, lastSent.length, lastDest, lastDestPort));
                    continue;
                }

                serverTid     = pkt.getAddress();
                serverTidPort = pkt.getPort();

                int op = TftpPacket.getOpcode(pkt);
                if (op == TftpPacket.OP_ERROR) {
                    System.err.println("[PUT] Server error " + TftpPacket.getErrorCode(pkt)
                                       + ": " + TftpPacket.getErrorMessage(pkt));
                    return RC_SERVER_ERROR;
                }
                if (op == TftpPacket.OP_ACK && TftpPacket.getBlockNumber(pkt) == 0) {
                    break ack0Loop;
                }
            }

            // Send file data in 512-byte blocks.
            // An empty final block is required when the file size is an exact multiple of 512.
            int blockNum = 1;
            int offset   = 0;
            boolean done = false;

            while (!done) {
                int length = Math.min(TftpPacket.BLOCK_SIZE, fileData.length - offset);
                done = (length < TftpPacket.BLOCK_SIZE);

                byte[] dataPacket = TftpPacket.buildData(blockNum, fileData, offset, length);

                retries = 0;
                socket.send(new DatagramPacket(dataPacket, dataPacket.length, serverTid, serverTidPort));

                ackLoop:
                while (true) {
                    byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                    DatagramPacket ackPkt = new DatagramPacket(recvBuf, recvBuf.length);

                    try {
                        socket.receive(ackPkt);
                    } catch (SocketTimeoutException e) {
                        if (++retries >= MAX_RETRIES) {
                            System.err.println("[PUT] Timeout on block " + blockNum);
                            return RC_TIMEOUT;
                        }
                        socket.send(new DatagramPacket(dataPacket, dataPacket.length, serverTid, serverTidPort));
                        continue;
                    }

                    int op = TftpPacket.getOpcode(ackPkt);
                    if (op == TftpPacket.OP_ERROR) {
                        System.err.println("[PUT] Server error " + TftpPacket.getErrorCode(ackPkt)
                                           + ": " + TftpPacket.getErrorMessage(ackPkt));
                        return RC_SERVER_ERROR;
                    }
                    if (op == TftpPacket.OP_ACK && TftpPacket.getBlockNumber(ackPkt) == blockNum) {
                        break ackLoop;
                    }
                }

                offset += length;
                blockNum++;
            }

            System.out.println("[PUT] Complete: " + filename);
            return RC_OK;
        }
    }
}
