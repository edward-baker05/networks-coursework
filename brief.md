# Assignment: Trivial File Transfer Protocol (TFTP)

The Trivial File Transfer Protocol (TFTP) is an Internet software utility for transferring files that is simpler to use than the File Transfer Protocol (FTP) but less capable. It is used where user authentication and directory visibility are not required. For example, it has been used by Cisco to transfer images of the operating system from/to routers and switches.

> **NOTE:** TFTP servers are normally bound to port **69** (a system/OS port). This is below **1024**, and therefore you cannot bind a Socket to it unless you have administrative access rights on the machine you are working on. To avoid any complications, you can use any port **> 1024** to bind the server socket.

---

## Task 1: Implementation of TFTP over UDP

For this task, you need to implement the Trivial File Transfer Protocol (TFTP), as specified in RFC 1350 (Links to an external site), in Java. You will submit source code for a client and server application that 'speak' the TFTP protocol. You will build your protocol on top of UDP. 



Compared to the specifications in the RFC, you will implement a slightly simplified version:

* **Octet mode only:** The files should be transferred as a raw sequence of bytes. Do not read, write, or transfer files as characters.
* **Limited error handling:** Support only error handling for when the server is unable to satisfy the request because the file cannot be found.
* **No duplication handling:** No support for error handling when data duplication occurs.

**Application Requirements:**
* The client and server applications should be simple Java console applications.
* The server should operate (i.e., read and write files) in the directory where it is started from.
* The server should support simultaneous file transfers to and from multiple clients.
* The client should just read command line arguments (or support a simple console-based menu—e.g., "press 1 to store file, press 2 to retrieve file") and execute user commands (i.e., reading or writing a file).

> **Hint:** The simplest way to implement timeouts is by calling the `setSoTimeout()` method on the `DatagramSocket` objects (assuming that you are using blocking I/O). If the timeout expires, a `java.net.SocketTimeoutException` is raised, though the `DatagramSocket` is still valid.

## Task 2: Implementation of Simpler TFTP over TCP

For this task, you will use TCP sockets to implement a protocol that operates like TFTP (i.e., supports only read and write operations). Given that TCP supports in-order, reliable data transport, **you should not implement** the relevant mechanisms described in RFC 1350 (ACKs, retransmissions). 

* The client and server applications should be equally simple, as in Task 1.
* The server must be able to handle multiple file transfers.

## Task 3: Interoperability with Existing Implementations

Given that the UDP version of the TFTP client and server that you will implement must adhere to the RFC, both the UDP versions of the client and server should interoperate with other TFTP servers and clients, respectively, regardless of the programming language they are written in. 

For this task, you are asked to demonstrate this interoperability by running:
1.  Your client with an existing third-party server.
2.  Your server with an existing third-party client.

---

## Marking Criteria

**Important:** You should make sure that your code compiles. **Code which does not compile will receive at most 20%.**

We will assess your assignment using the following criteria:

| Component | Weight | Criteria |
| :--- | :---: | :--- |
| **TFTP UDP Server** | 30% | Is the server-side of the protocol fully and correctly implemented (based on the RFC)? *Includes read/write requests, acknowledgments, timeouts, error handling, and support for simultaneous file transfers.* |
| **TFTP UDP Client** | 20% | Is the client-side of the protocol fully and correctly implemented (based on the RFC)? *Includes read/write requests, acknowledgments, timeouts, and error handling.* |
| **TFTP TCP Server** | 10% | Is the server-side of the protocol fully and correctly implemented? *Includes read/write requests, error handling, and support for simultaneous file transfers.* |
| **TFTP TCP Client** | 10% | Is the client-side of the protocol fully and correctly implemented? *Includes read/write requests and error handling.* |

### Video Submissions (30% Total)

#### Voice-over Screen Recording Video (25%)
You must submit a voice-over screen recording video (**maximum length of 7 minutes**) in which you answer the technical questions listed below while showing the relevant parts of the codebase. Presentation skills will not be assessed; marking will focus only on the technical depth and completeness of your answers.

*We provide the following files: `512.txt`, `medium.txt`, `large.txt`. Complete the sub-tasks below using one or more of those to base your discussion:*

1.  **Packet Header (5%):** Explain the code that you wrote to build the packet header, including opcodes and sequence numbers.
2.  **Packetization (5%):** Explain how packetization works in your code.
3.  **Initiating Requests (5%):** Using the debugger, walk through how WRQ and RRQ requests are initiated.
4.  **Error Handling (5%):** Using the debugger, walk through an example of a failing read request (RRQ) to show your implementation of Error Code 1 (File not found).
5.  **Completion Detection (5%):** Using the debugger, walk through the end of a TFTP read transfer (RRQ) and show exactly how your client detects the final DATA packet and how your server handles the final ACK.

#### Interoperability Video (5%)
You must submit a voice-over screen recording video (**maximum length of 2 minutes**) where you show:
1.  Your client writing and, subsequently, reading the provided `test_image.jpg` file to any TFTP server available online.
2.  A TFTP client available online writing and, subsequently, reading the same file to your TFTP server. 

*For both cases, you should open the file as received by the client to check that the image is not corrupted; this should be evident in the submitted recording.*

---

## Submission Guidelines

You will need to submit a **.zip file** containing the following:

* **Well-formatted Java source code** organized into 4 separate IntelliJ Maven projects that can be compiled and run. The projects must be exactly named:
    * `TFTP-UDP-Server`
    * `TFTP-UDP-Client`
    * `TFTP-TCP-Server`
    * `TFTP-TCP-Client`
* **The two voice-over screen recording videos** described above.

**Penalties & Notes:**
* There will be a penalty of **10 marks** if you fail to submit Maven projects.
* For the recordings requested above, we will only mark the content included in the maximum allowed durations.
* **Failure to submit source code**, as described in the first bullet, will result in a **zero mark** as we will not be able to assess your programming effort.

