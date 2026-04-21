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
 * Usage (command line):
 *   java tftp.udp.client.TftpUdpClient get <filename> [host] [port]
 *   java tftp.udp.client.TftpUdpClient put <filename> [host] [port]
 *
 * With no arguments an interactive menu is displayed.
 *
 * Defaults: host = localhost, port = 6969.
 *
 * Protocol notes:
 *   – The client's socket is bound to an ephemeral (OS-chosen) port (the client TID).
 *   – The server's TID is captured from the source port of the first response packet.
 *   – On timeout the last sent packet is retransmitted (up to MAX_RETRIES times).
 *   – Packets from unexpected source addresses are rejected with ERROR code 5.
 */
public class TftpUdpClient {

    private static final int TIMEOUT_MS  = 2000;
    private static final int MAX_RETRIES = 5;

    /** doGet/doPut return codes. */
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
            // Interactive menu
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

            // Send RRQ to the server's well-known port
            byte[] rrq = TftpPacket.buildRRQ(filename);
            socket.send(new DatagramPacket(rrq, rrq.length, serverAddr, serverPort));

            // Track server TID (established from the first response packet)
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

                // First response establishes the server TID
                if (serverTid == null) {
                    serverTid     = pkt.getAddress();
                    serverTidPort = pkt.getPort();
                } else if (!pkt.getAddress().equals(serverTid) || pkt.getPort() != serverTidPort) {
                    // Packet from unknown source — send error, continue waiting
                    byte[] err = TftpPacket.buildError(TftpPacket.ERR_UNKNOWN_TID, "Unknown transfer ID");
                    socket.send(new DatagramPacket(err, err.length, pkt.getAddress(), pkt.getPort()));
                    continue;
                }

                int op = TftpPacket.getOpcode(pkt);

                if (op == TftpPacket.OP_ERROR) {
                    System.err.println("[GET] Server error " + TftpPacket.getErrorCode(pkt)
                                       + ": " + TftpPacket.getErrorMessage(pkt));
                    return RC_SERVER_ERROR;
                }

                if (op != TftpPacket.OP_DATA) continue;

                int blockNum = TftpPacket.getBlockNumber(pkt);

                // Duplicate of the previous block → re-ACK without writing
                if (blockNum == ((expectedBlock - 1) & 0xFFFF)) {
                    byte[] ack = TftpPacket.buildAck(blockNum);
                    socket.send(new DatagramPacket(ack, ack.length, serverTid, serverTidPort));
                    lastSent     = ack;
                    lastDest     = serverTid;
                    lastDestPort = serverTidPort;
                    continue;
                }

                // Unexpected block number — discard
                if (blockNum != (expectedBlock & 0xFFFF)) continue;

                byte[] data = TftpPacket.getData(pkt);
                fileData.write(data);

                // Acknowledge the block
                byte[] ack = TftpPacket.buildAck(blockNum);
                socket.send(new DatagramPacket(ack, ack.length, serverTid, serverTidPort));
                lastSent     = ack;
                lastDest     = serverTid;
                lastDestPort = serverTidPort;

                if (data.length < TftpPacket.BLOCK_SIZE) {
                    break;  // Final block (data < 512 bytes signals end of transfer)
                }

                // 16-bit block number wrap
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

            // Send WRQ to the server's well-known port
            byte[] wrq = TftpPacket.buildWRQ(filename);
            socket.send(new DatagramPacket(wrq, wrq.length, serverAddr, serverPort));

            InetAddress serverTid     = null;
            int         serverTidPort = -1;

            // Wait for ACK(0) — the server's acceptance of the WRQ
            byte[] lastSent  = wrq;
            InetAddress lastDest = serverAddr;
            int lastDestPort     = serverPort;
            int retries          = 0;

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

                // First response establishes the server TID
                serverTid     = pkt.getAddress();
                serverTidPort = pkt.getPort();

                int op = TftpPacket.getOpcode(pkt);
                if (op == TftpPacket.OP_ERROR) {
                    System.err.println("[PUT] Server error " + TftpPacket.getErrorCode(pkt)
                                       + ": " + TftpPacket.getErrorMessage(pkt));
                    return RC_SERVER_ERROR;
                }
                if (op == TftpPacket.OP_ACK && TftpPacket.getBlockNumber(pkt) == 0) {
                    break ack0Loop;  // WRQ accepted
                }
                // Unexpected packet — ignore
            }

            // Send file in 512-byte blocks.
            // If the file size is an exact multiple of 512, a final empty block
            // (length = 0) is required to signal end of transfer (RFC §5).
            int blockNum = 1;
            int offset   = 0;
            boolean done = false;

            while (!done) {
                int length = Math.min(TftpPacket.BLOCK_SIZE, fileData.length - offset);
                done = (length < TftpPacket.BLOCK_SIZE);

                byte[] dataPacket = TftpPacket.buildData(blockNum, fileData, offset, length);

                retries = 0;
                socket.send(new DatagramPacket(dataPacket, dataPacket.length, serverTid, serverTidPort));

                // Wait for ACK(blockNum)
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

                    if (!ackPkt.getAddress().equals(serverTid) || ackPkt.getPort() != serverTidPort) {
                        byte[] err = TftpPacket.buildError(TftpPacket.ERR_UNKNOWN_TID, "Unknown transfer ID");
                        socket.send(new DatagramPacket(err, err.length, ackPkt.getAddress(), ackPkt.getPort()));
                        continue;
                    }

                    int op = TftpPacket.getOpcode(ackPkt);
                    if (op == TftpPacket.OP_ERROR) {
                        System.err.println("[PUT] Server error " + TftpPacket.getErrorCode(ackPkt)
                                           + ": " + TftpPacket.getErrorMessage(ackPkt));
                        return RC_SERVER_ERROR;
                    }
                    if (op == TftpPacket.OP_ACK
                            && TftpPacket.getBlockNumber(ackPkt) == (blockNum & 0xFFFF)) {
                        break ackLoop;  // Correct ACK
                    }
                    // Old / out-of-order ACK — keep waiting
                }

                offset += length;
                blockNum++;
            }

            System.out.println("[PUT] Complete: " + filename);
            return RC_OK;
        }
    }
}
