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

/**
 * Handles a single TFTP transfer (RRQ or WRQ) on a fresh ephemeral UDP socket.
 * Opening a new socket per transfer lets the welcome port serve multiple clients simultaneously.
 * Unacknowledged packets are retransmitted up to MAX_RETRIES times before aborting.
 */
public class TftpTransferHandler implements Runnable {

    private static final int TIMEOUT_MS  = 2000;
    private static final int MAX_RETRIES = 5;

    private final int opcode;
    private final byte[] requestData;
    private final InetAddress clientAddr;
    private final int clientPort;
    private final Path baseDir;

    public TftpTransferHandler(int opcode, byte[] requestData,
                               InetAddress clientAddr, int clientPort,
                               Path baseDir) {
        this.opcode      = opcode;
        this.requestData = requestData;
        this.clientAddr  = clientAddr;
        this.clientPort  = clientPort;
        this.baseDir     = baseDir;
    }

    @Override
    public void run() {
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

    private void handleRRQ(DatagramSocket socket, String filename) throws IOException {
        Path filePath = baseDir.resolve(filename);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            sendError(socket, clientAddr, clientPort,
                      TftpPacket.ERR_FILE_NOT_FOUND, "File not found");
            System.out.println("[RRQ] File not found: " + filename
                               + " (from " + clientAddr.getHostAddress() + ":" + clientPort + ")");
            return;
        }

        System.out.println("[RRQ] " + clientAddr.getHostAddress() + ":" + clientPort
                           + " <- " + filename + " (" + Files.size(filePath) + " bytes)");

        int blockNum = 1;
        byte[] blockBuf = new byte[TftpPacket.BLOCK_SIZE];

        try (InputStream in = Files.newInputStream(filePath)) {
            boolean done = false;

            while (!done) {
                int bytesRead = in.readNBytes(blockBuf, 0, TftpPacket.BLOCK_SIZE);
                done = (bytesRead < TftpPacket.BLOCK_SIZE);

                byte[] dataPacket = TftpPacket.buildData(blockNum, blockBuf, 0, bytesRead);

                int retries = 0;
                socket.send(new DatagramPacket(dataPacket, dataPacket.length, clientAddr, clientPort));

                ackLoop:
                while (true) {
                    byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                    DatagramPacket resp = new DatagramPacket(recvBuf, recvBuf.length);

                    try {
                        socket.receive(resp);
                    } catch (SocketTimeoutException e) {
                        if (++retries >= MAX_RETRIES) {
                            System.err.println("[RRQ] Timeout: " + filename + " block " + blockNum);
                            return;
                        }
                        socket.send(new DatagramPacket(dataPacket, dataPacket.length, clientAddr, clientPort));
                        continue;
                    }

                    int op = TftpPacket.getOpcode(resp);
                    if (op == TftpPacket.OP_ERROR) {
                        System.err.println("[RRQ] Client error: " + TftpPacket.getErrorMessage(resp));
                        return;
                    }
                    if (op == TftpPacket.OP_ACK && TftpPacket.getBlockNumber(resp) == blockNum) {
                        break ackLoop;
                    }
                }

                blockNum++;
                if (blockNum % 10000 == 0) {
                    System.out.println("[Port " + clientPort + "] Sent block: " + blockNum);
                }
            }
        }

        System.out.println("[RRQ] Complete: " + filename);
    }

    private void handleWRQ(DatagramSocket socket, String filename) throws IOException {
        System.out.println("[WRQ] " + clientAddr.getHostAddress() + ":" + clientPort
                           + " -> " + filename);

        byte[] ack0 = TftpPacket.buildAck(0);
        socket.send(new DatagramPacket(ack0, ack0.length, clientAddr, clientPort));

        int expectedBlock = 1;
        byte[] lastAck = ack0;

        try (OutputStream out = Files.newOutputStream(baseDir.resolve(filename))) {

            int retries = 0;
            while (true) {
                byte[] recvBuf = new byte[TftpPacket.MAX_PACKET_SIZE];
                DatagramPacket pkt = new DatagramPacket(recvBuf, recvBuf.length);

                try {
                    socket.receive(pkt);
                    retries = 0;
                } catch (SocketTimeoutException e) {
                    if (++retries >= MAX_RETRIES) {
                        System.err.println("[WRQ] Timeout waiting for block " + expectedBlock);
                        return;
                    }
                    socket.send(new DatagramPacket(lastAck, lastAck.length, clientAddr, clientPort));
                    continue;
                }

                int op = TftpPacket.getOpcode(pkt);
                if (op == TftpPacket.OP_ERROR) {
                    System.err.println("[WRQ] Client error: " + TftpPacket.getErrorMessage(pkt));
                    return;
                }
                if (op != TftpPacket.OP_DATA) continue;

                int blockNum = TftpPacket.getBlockNumber(pkt);
                if (blockNum != expectedBlock) continue;

                byte[] data = TftpPacket.getData(pkt);
                out.write(data);

                lastAck = TftpPacket.buildAck(blockNum);
                socket.send(new DatagramPacket(lastAck, lastAck.length, clientAddr, clientPort));

                if (data.length < TftpPacket.BLOCK_SIZE) {
                    break;
                }

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
