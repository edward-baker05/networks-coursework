package tftp.tcp.client;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

/**
 * Simplified TFTP-over-TCP Client.
 *
 * Usage (command line):
 *   java tftp.tcp.client.TftpTcpClient get <filename> [host] [port]
 *   java tftp.tcp.client.TftpTcpClient put <filename> [host] [port]
 *
 * With no arguments an interactive menu is displayed.
 *
 * Defaults: host = localhost, port = 6970.
 *
 * TCP provides ordered, reliable delivery so there are no per-block
 * acknowledgements or retransmissions.  Each operation opens one TCP
 * connection, exchanges a single request/response, and closes.
 */
public class TftpTcpClient {

    /** doGet/doPut return codes. */
    public static final int RC_OK              = 0;
    public static final int RC_SERVER_ERROR    = 1;
    public static final int RC_LOCAL_NOT_FOUND = 3;
    public static final int RC_PROTOCOL_ERROR  = 4;

    public static void main(String[] args) throws Exception {
        String operation = null;
        String filename  = null;
        String host      = "localhost";
        int    port      = 6970;

        if (args.length >= 2) {
            operation = args[0].toLowerCase();
            filename  = args[1];
            if (args.length >= 3) host = args[2];
            if (args.length >= 4) port = Integer.parseInt(args[3]);
        } else {
            Scanner sc = new Scanner(System.in);
            System.out.println("=== TFTP TCP Client ===");
            System.out.print("Server host [localhost]: ");
            String h = sc.nextLine().trim();
            if (!h.isEmpty()) host = h;
            System.out.print("Server port [6970]: ");
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

        Path baseDir = Paths.get(".");

        int rc = switch (operation) {
            case "get" -> doGet(host, port, filename, baseDir);
            case "put" -> doPut(host, port, filename, baseDir);
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

    static int doGet(String host, int port, String filename, Path baseDir) throws Exception {
        System.out.println("[GET] " + filename + " from " + host + ":" + port);

        try (Socket socket = new Socket(host, port);
             DataInputStream  in  = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // Send RRQ
            TftpTcpProtocol.writeRRQ(out, filename);

            // Read response opcode
            int opcode = in.readShort() & 0xFFFF;

            if (opcode == TftpTcpProtocol.OP_ERROR) {
                int code  = TftpTcpProtocol.readErrorCode(in);
                String msg = TftpTcpProtocol.readNullTermString(in);
                System.err.println("[GET] Server error " + code + ": " + msg);
                return RC_SERVER_ERROR;
            }

            if (opcode != TftpTcpProtocol.OP_DATA) {
                System.err.println("[GET] Unexpected opcode: " + opcode);
                return RC_PROTOCOL_ERROR;
            }

            // Read the complete file
            byte[] data = TftpTcpProtocol.readData(in);
            Files.write(baseDir.resolve(filename), data);
            System.out.println("[GET] Complete: " + filename + " (" + data.length + " bytes)");
            return RC_OK;
        }
    }

    // -------------------------------------------------------------------------
    // PUT (WRQ): send a local file to the server.
    // -------------------------------------------------------------------------

    static int doPut(String host, int port, String filename, Path baseDir) throws Exception {
        Path filePath = baseDir.resolve(filename);
        if (!Files.exists(filePath)) {
            System.err.println("[PUT] Local file not found: " + filename);
            return RC_LOCAL_NOT_FOUND;
        }

        byte[] data = Files.readAllBytes(filePath);
        System.out.println("[PUT] " + filename + " (" + data.length + " bytes)"
                           + " to " + host + ":" + port);

        try (Socket socket = new Socket(host, port);
             DataInputStream  in  = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            // Send WRQ followed immediately by the DATA message
            TftpTcpProtocol.writeWRQ(out, filename);
            TftpTcpProtocol.writeData(out, data);

            // Check whether the server sends an error response before closing
            try {
                int opcode = in.readShort() & 0xFFFF;
                if (opcode == TftpTcpProtocol.OP_ERROR) {
                    int code  = TftpTcpProtocol.readErrorCode(in);
                    String msg = TftpTcpProtocol.readNullTermString(in);
                    System.err.println("[PUT] Server error " + code + ": " + msg);
                    return RC_SERVER_ERROR;
                }
            } catch (EOFException ignored) {
                // Server closed the connection after writing — normal success path
            }

            System.out.println("[PUT] Complete: " + filename);
            return RC_OK;
        }
    }
}
