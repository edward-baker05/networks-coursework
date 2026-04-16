package tftp.udp.server;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Handles a single TFTP RRQ or WRQ transfer on its own ephemeral UDP socket.
 *
 * The ephemeral socket's port is this connection's server-side TID. Opening a
 * fresh socket per transfer is the RFC-mandated approach that allows the single
 * well-known welcome port to serve many simultaneous clients.
 *
 * Timeouts and retransmissions follow RFC 1123 §4.2.3:
 *   – Each unacknowledged packet is retransmitted up to MAX_RETRIES times.
 *   – A SocketTimeoutException after TIMEOUT_MS triggers a retransmit.
 *
 * Error handling implemented:
 *   – Error code 1 (File not found) on RRQ for a missing file.
 *   – Error code 5 (Unknown TID) for packets arriving from unexpected sources.
 */
public class TftpTransferHandler implements Runnable {

    private static final int TIMEOUT_MS  = 2000;
    private static final int MAX_RETRIES = 5;

    private final int opcode;
    private final byte[] requestData;
    private final InetAddress clientAddr;
    private final int clientPort;

    public TftpTransferHandler(int opcode, byte[] requestData,
                               InetAddress clientAddr, int clientPort) {
        this.opcode      = opcode;
        this.requestData = requestData;
        this.clientAddr  = clientAddr;
        this.clientPort  = clientPort;
    }

    @Override
    public void run() {
        // Wrap in a fake DatagramPacket so we can reuse TftpPacket.parseRequest
        DatagramPacket fakePkt = new DatagramPacket(requestData, requestData.length);
        String filename = TftpPacket.parseRequest(fakePkt)[0];

        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(TIMEOUT_MS);

            if (opcode == TftpPacket.OP_RRQ) {
                handleRRQ(socket, filename);
            } else {
                handleWRQ(socket, filename);
            }
        } catch (Exception e) {
            System.err.println("[Transfer error] " + filename + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // RRQ: server reads the local file and sends it as DATA blocks.
    //      Client acknowledges each block before the next is sent (lock-step).
    // -------------------------------------------------------------------------

    private void handleRRQ(DatagramSocket socket, String filename) throws IOException {
        Path filePath = Paths.get(filename);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            sendError(socket, clientAddr, clientPort,
                      TftpPacket.ERR_FILE_NOT_FOUND, "File not found");
            System.out.println("[RRQ] File not found: " + filename
                               + " (from " + clientAddr.getHostAddress() + ":" + clientPort + ")");
            return;
        }

        System.out.println("[RRQ] " + clientAddr.getHostAddress() + ":" + clientPort
                           + " <- " + filename + " (" + Files.size(filePath) + " bytes)");

        // The client's address and port from the welcome socket are the initial peer.
        // After the first ACK arrives the server TID is confirmed (peerLocked = true).
        InetAddress peerAddr = clientAddr;
        int peerPort = clientPort;
        boolean peerLocked = false;

        int blockNum = 1;
        byte[] blockBuf = new byte[TftpPacket.BLOCK_SIZE];

        try (InputStream in = Files.newInputStream(filePath)) {
            boolean done = false;

            while (!done) {
                // Read up to 512 bytes. bytesRead < BLOCK_SIZE signals end of file.
                int bytesRead = in.readNBytes(blockBuf, 0, TftpPacket.BLOCK_SIZE);
                done = (bytesRead < TftpPacket.BLOCK_SIZE);

                // Build DATA packet: header(4) + data(bytesRead)
                byte[] dataPacket = TftpPacket.buildData(blockNum, blockBuf, 0, bytesRead);

                // Send DATA(blockNum), wait for ACK(blockNum) with retransmit on timeout.
                int retries = 0;
                socket.send(new DatagramPacket(dataPacket, dataPacket.length, peerAddr, peerPort));

                ackLoop:
                while (true) {
                    byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                    DatagramPacket resp = new DatagramPacket(recvBuf, recvBuf.length);

                    try {
                        socket.receive(resp);
                    } catch (SocketTimeoutException e) {
                        if (++retries >= MAX_RETRIES) {
                            System.err.println("[RRQ] Timeout: " + filename
                                               + " block " + blockNum + " after "
                                               + MAX_RETRIES + " retries");
                            return;
                        }
                        // Retransmit the current DATA block
                        socket.send(new DatagramPacket(dataPacket, dataPacket.length, peerAddr, peerPort));
                        continue;
                    }

                    // TID check: first ACK locks in the peer's ephemeral port.
                    if (!peerLocked) {
                        peerAddr  = resp.getAddress();
                        peerPort  = resp.getPort();
                        peerLocked = true;
                    } else if (!resp.getAddress().equals(peerAddr) || resp.getPort() != peerPort) {
                        // Packet from an unknown source – send error, do not abort the transfer.
                        sendError(socket, resp.getAddress(), resp.getPort(),
                                  TftpPacket.ERR_UNKNOWN_TID, "Unknown transfer ID");
                        continue;
                    }

                    int op = TftpPacket.getOpcode(resp);
                    if (op == TftpPacket.OP_ERROR) {
                        System.err.println("[RRQ] Client error: " + TftpPacket.getErrorMessage(resp));
                        return;
                    }
                    if (op == TftpPacket.OP_ACK) {
                        int ackBlock = TftpPacket.getBlockNumber(resp);
                        if (ackBlock == (blockNum & 0xFFFF)) {
                            break ackLoop;  // Correct ACK received
                        }
                        // Duplicate / out-of-order ACK — ignore, keep waiting
                    }
                }

                // Advance block number (16-bit unsigned wrap: 65535 -> 0)
                blockNum++;
            }
        }

        System.out.println("[RRQ] Complete: " + filename);
    }

    // -------------------------------------------------------------------------
    // WRQ: server sends ACK(0), then receives DATA blocks from the client.
    //      Each DATA block is acknowledged before the client sends the next.
    // -------------------------------------------------------------------------

    private void handleWRQ(DatagramSocket socket, String filename) throws IOException {
        System.out.println("[WRQ] " + clientAddr.getHostAddress() + ":" + clientPort
                           + " -> " + filename);

        // RFC 1350 §4: respond to WRQ with ACK(0) to signal readiness.
        byte[] ack0 = TftpPacket.buildAck(0);
        socket.send(new DatagramPacket(ack0, ack0.length, clientAddr, clientPort));

        InetAddress peerAddr = clientAddr;
        int peerPort = clientPort;
        boolean peerLocked = false;

        int expectedBlock = 1;
        byte[] lastAck = ack0;

        try (OutputStream out = Files.newOutputStream(Paths.get(filename))) {

            while (true) {
                byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);

                try {
                    socket.receive(pkt);
                } catch (SocketTimeoutException e) {
                    System.err.println("[WRQ] Timeout waiting for block " + expectedBlock);
                    return;
                }

                // TID check
                if (!peerLocked) {
                    peerAddr  = pkt.getAddress();
                    peerPort  = pkt.getPort();
                    peerLocked = true;
                } else if (!pkt.getAddress().equals(peerAddr) || pkt.getPort() != peerPort) {
                    sendError(socket, pkt.getAddress(), pkt.getPort(),
                              TftpPacket.ERR_UNKNOWN_TID, "Unknown transfer ID");
                    continue;
                }

                int op = TftpPacket.getOpcode(pkt);
                if (op == TftpPacket.OP_ERROR) {
                    System.err.println("[WRQ] Client error: " + TftpPacket.getErrorMessage(pkt));
                    return;
                }
                if (op != TftpPacket.OP_DATA) continue;

                int blockNum = TftpPacket.getBlockNumber(pkt);

                // Duplicate of the previously acknowledged block → re-send the last ACK.
                if (blockNum == ((expectedBlock - 1) & 0xFFFF)) {
                    socket.send(new DatagramPacket(lastAck, lastAck.length, peerAddr, peerPort));
                    continue;
                }

                // Unexpected block number — discard.
                if (blockNum != (expectedBlock & 0xFFFF)) continue;

                byte[] data = TftpPacket.getData(pkt);
                out.write(data);

                // Acknowledge this block
                lastAck = TftpPacket.buildAck(blockNum);
                socket.send(new DatagramPacket(lastAck, lastAck.length, peerAddr, peerPort));

                if (data.length < TftpPacket.BLOCK_SIZE) {
                    // Final block (RFC: data < 512 bytes signals end of transfer)
                    break;
                }

                // 16-bit block number wrap
                expectedBlock++;
            }
        }

        System.out.println("[WRQ] Complete: " + filename);
    }

    private void sendError(DatagramSocket socket, InetAddress addr, int port,
                           int code, String msg) {
        try {
            byte[] err = TftpPacket.buildError(code, msg);
            socket.send(new DatagramPacket(err, err.length, addr, port));
        } catch (Exception ignored) {}
    }
}
