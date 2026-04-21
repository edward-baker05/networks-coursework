package tftp.tcp.server;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Handles a single TFTP-over-TCP client connection.
 *
 * Reads an opcode from the stream, dispatches to RRQ or WRQ handling,
 * then closes the socket.  No per-block ACKs are used because TCP already
 * guarantees ordered, reliable delivery.
 */
public class TftpTcpHandler implements Runnable {

    private final Socket socket;
    private final Path baseDir;

    public TftpTcpHandler(Socket socket, Path baseDir) {
        this.socket = socket;
        this.baseDir = baseDir;
    }

    @Override
    public void run() {
        String remote = socket.getRemoteSocketAddress().toString();
        try (socket;
             DataInputStream  in  = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            int opcode;
            try {
                opcode = in.readShort() & 0xFFFF;
            } catch (EOFException e) {
                // Client disconnected without sending anything
                return;
            }

            if (opcode != TftpTcpProtocol.OP_RRQ && opcode != TftpTcpProtocol.OP_WRQ) {
                TftpTcpProtocol.writeError(out, TftpTcpProtocol.ERR_ILLEGAL_OP,
                                           "Expected RRQ (1) or WRQ (2)");
                return;
            }

            String[] req = TftpTcpProtocol.readRequest(in);
            String filename = req[0];

            if (opcode == TftpTcpProtocol.OP_RRQ) {
                handleRRQ(in, out, filename, remote);
            } else {
                handleWRQ(in, out, filename, remote);
            }

        } catch (Exception e) {
            System.err.println("[Handler error] " + remote + ": " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // RRQ: read the requested file and stream it to the client as one DATA message.
    // -------------------------------------------------------------------------

    private void handleRRQ(DataInputStream in, DataOutputStream out,
                           String filename, String remote) throws IOException {
        Path filePath = baseDir.resolve(filename);

        if (!Files.exists(filePath) || !Files.isRegularFile(filePath)) {
            TftpTcpProtocol.writeError(out, TftpTcpProtocol.ERR_FILE_NOT_FOUND,
                                       "File not found");
            System.out.println("[RRQ] File not found: " + filename
                               + " (for " + remote + ")");
            return;
        }

        System.out.println("[RRQ] " + remote + " <- " + filename
                           + " (" + Files.size(filePath) + " bytes)");
        byte[] data = Files.readAllBytes(filePath);
        TftpTcpProtocol.writeData(out, data);
        System.out.println("[RRQ] Complete: " + filename);
    }

    // -------------------------------------------------------------------------
    // WRQ: receive a DATA message from the client and write it to a local file.
    // -------------------------------------------------------------------------

    private void handleWRQ(DataInputStream in, DataOutputStream out,
                           String filename, String remote) throws IOException {
        System.out.println("[WRQ] " + remote + " -> " + filename);

        try {
            // The client prefixes the DATA payload with the DATA opcode (matching
            // the same writeData method used for the server's RRQ response).
            // Consume the opcode before reading the length-prefixed file bytes.
            int dataOpcode = in.readShort() & 0xFFFF;
            if (dataOpcode != TftpTcpProtocol.OP_DATA) {
                TftpTcpProtocol.writeError(out, TftpTcpProtocol.ERR_ILLEGAL_OP,
                                           "Expected DATA opcode");
                return;
            }
            byte[] data = TftpTcpProtocol.readData(in);
            Files.write(baseDir.resolve(filename), data);
            System.out.println("[WRQ] Complete: " + filename + " (" + data.length + " bytes)");
        } catch (IOException e) {
            TftpTcpProtocol.writeError(out, TftpTcpProtocol.ERR_DISK_FULL,
                                       "Write failed: " + e.getMessage());
        }
    }
}
