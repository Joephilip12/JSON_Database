package client;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Path;

/**
 * Starts the client, prepares one JSON request, and prints the server reply.
 *
 * <p>Follow main, parseRequest, and exchange to trace a request from the
 * command line to the socket.</p>
 */
public final class Main {
    /** Loopback port used by the server. */
    private static final int PORT = 23456;
    /**
     * Folder used by -in, relative to the directory where Java is launched.
     * Path.of joins the names into a path. Reading happens later.
     */
    private static final Path REQUEST_DIRECTORY =
            Path.of("src", "client", "data");

    /** Prevents construction of the application entry point. */
    private Main() {
    }

    /**
     * Reads the request and connects to the local database server.
     *
     * @param args the request type, key and value, or the input filename
     * @throws IOException if the server cannot be reached or the exchange fails
     */
    // The CLI prints its status and connects to the IPv4 loopback listener.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidUsingHardCodedIP"})
    public static void main(final String[] args) throws IOException {
        // Declare the variable here so the socket code can use it later.
        // A successful parse assigns it once. Both error paths return early.
        final String request;
        try {
            // The second argument becomes the requestDirectory parameter.
            // The returned JSON text becomes the value of request.
            request = parseRequest(args, REQUEST_DIRECTORY);
        } catch (ParameterException exception) {
            // An invalid option goes to the standard error stream, stderr.
            System.err.println(exception.getMessage());
            return;
        } catch (IOException exception) {
            // A file can fail to open even when the options are valid.
            System.err.println("Could not read the request: "
                    + exception.getMessage());
            return;
        }

        // The loopback address points back to the same computer.
        final InetAddress address = InetAddress.getByName("127.0.0.1");
        // Opening the Socket connects to the server's TCP listening port.
        // Try-with-resources closes it when this block ends.
        try (Socket connection = new Socket(address, PORT)) {
            System.out.println("Client started!");
            // Pass both the open connection and the JSON text to exchange.
            exchange(connection, request);
        }
    }

    /**
     * Parses the command line and builds the request.
     *
     * @param args the client options
     * @param requestDirectory the base directory for file requests
     * @return the request JSON as text
     * @throws IOException if a requested file cannot be read
     */
    static String parseRequest(final String[] args, final Path requestDirectory)
            throws IOException {
        // Store the directory in the object that will receive the options.
        final ClientArguments arguments = new ClientArguments(requestDirectory);
        // The builder configures the parser before it reads any input.
        final JCommander parser = JCommander.newBuilder()
                // Read @Parameter annotations and fill in this object.
                .addObject(arguments)
                // Use this program name when displaying usage information.
                .programName("client.Main")
                .build();
        // For example, "-in", "table-set.json" sets the inputFile field.
        // final fixes the object reference. The parser can fill its fields.
        parser.parse(args);
        // toRequest reads the file or builds JSON from the parsed options.
        // The caller receives this String and assigns it to request.
        return arguments.toRequest();
    }

    /**
     * Writes a UTF-framed request and prints the corresponding response.
     *
     * @param connection the socket connected to the server
     * @param request the request text
     * @throws IOException if the exchange fails
     */
    // Sent and Received are part of the CLI output contract.
    @SuppressWarnings("PMD.SystemPrintln")
    static void exchange(final Socket connection, final String request)
            throws IOException {
        // Both wrappers use streams from the same connected socket.
        // Try-with-resources closes the wrappers on success or failure.
        try (DataInputStream input =
                     new DataInputStream(connection.getInputStream());
             DataOutputStream output =
                     new DataOutputStream(connection.getOutputStream())) {
            // writeUTF sends a length prefix followed by the encoded text.
            // The matching readUTF can identify exactly one message.
            output.writeUTF(request);
            // Ask the output stream to forward any buffered bytes.
            output.flush();
            System.out.println("Sent: " + request);

            // Wait for the reply on this connection and read one message.
            final String response = input.readUTF();
            System.out.println("Received: " + response);
        }
    }
}
