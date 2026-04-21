package tftp.udp.client;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;

/**
 * Tiny helper wrapping a {@link DatagramSocket} for client-side tests.
 *
 * Acts as a scripted server: we receive a packet from the client, assert
 * something about it, then reply with something specific. The first request
 * locks the client peer so that subsequent {@link #reply} calls target the
 * correct address/port automatically.
 */
final class UdpMockPeer implements AutoCloseable {

    private final DatagramSocket socket;
    private InetAddress clientAddr;
    private int clientPort = -1;

    UdpMockPeer() throws IOException {
        this.socket = new DatagramSocket();
        this.socket.setSoTimeout(5000);
    }

    int port() {
        return socket.getLocalPort();
    }

    /** Block until we receive the next packet from the client. */
    DatagramPacket awaitPacket() throws IOException {
        byte[] buf = new byte[TftpPacket.MAX_PACKET_SIZE];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        socket.receive(pkt);
        if (clientAddr == null) {
            clientAddr = pkt.getAddress();
            clientPort = pkt.getPort();
        }
        return pkt;
    }

    /** Block for up to {@code ms} milliseconds; returns null on timeout. */
    DatagramPacket tryAwaitPacket(int ms) throws IOException {
        int prev = socket.getSoTimeout();
        socket.setSoTimeout(ms);
        try {
            return awaitPacket();
        } catch (SocketTimeoutException e) {
            return null;
        } finally {
            socket.setSoTimeout(prev);
        }
    }

    /** Reply with arbitrary bytes to the locked client address/port. */
    void reply(byte[] bytes) throws IOException {
        socket.send(new DatagramPacket(bytes, bytes.length, clientAddr, clientPort));
    }

    /** Reply from a foreign (intruder) source to test the client's TID check. */
    void replyFrom(DatagramSocket other, byte[] bytes) throws IOException {
        other.send(new DatagramPacket(bytes, bytes.length, clientAddr, clientPort));
    }

    InetAddress clientAddress() { return clientAddr; }
    int clientTid() { return clientPort; }

    @Override
    public void close() {
        socket.close();
    }
}
