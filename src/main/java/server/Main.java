package server;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Accepts local TCP clients and handles their JSON requests.
 *
 * <p>The main thread accepts sockets. Worker threads read requests, call
 * the shared Database, and send replies.</p>
 */
public final class Main {
    /** Reports failures in individual client connections. */
    private static final Logger LOGGER = Logger.getLogger(Main.class.getName());
    /** Loopback port shared by the client and server. */
    private static final int PORT = 23456;
    /**
     * Requested capacity of the operating system's pending connection queue.
     * WORKERS controls the number of tasks we can handle at the same time.
     */
    private static final int BACKLOG = 50;
    /** Maximum number of active worker tasks. */
    private static final int WORKERS = 4;
    /** Socket read timeout in milliseconds. */
    private static final int READ_TIMEOUT_MILLIS = 10_000;

    /** Reason returned for an invalid protocol request. */
    private static final String INVALID_REQUEST = "Invalid request";
    /** Reason returned for a missing record. */
    private static final String NO_SUCH_KEY = "No such key";
    /** Operation that closes the listener after its response. */
    private static final String EXIT = "exit";

    /** Prevents construction of the application entry point. */
    private Main() {
    }

    /**
     * Starts the server and listens until it receives an exit request.
     *
     * @param args unused command-line arguments
     * @throws IOException if the listener or database cannot be initialized
     */
    // The protocol requires console output and an IPv4 loopback listener.
    @SuppressWarnings({"PMD.SystemPrintln", "PMD.AvoidUsingHardCodedIP"})
    public static void main(final String[] args) throws IOException {
        // Bind to the loopback address so clients connect from this computer.
        final InetAddress address = InetAddress.getByName("127.0.0.1");
        // This path is relative to the server process's working directory.
        final Path databaseFile = Path.of("src", "server", "data", "db.json");
        // Every worker uses this instance, so they share its database locks.
        final Database database = new Database(databaseFile);
        // The pool reuses a fixed number of threads across connections.
        final ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        // Open the listener and let try-with-resources close it on exit.
        try (ServerSocket server = new ServerSocket(PORT, BACKLOG, address)) {
            // Create the database file if this is the first run.
            database.initialize();
            System.out.println("Server started!");
            // Keep the main thread in the accept loop until shutdown.
            acceptConnections(server, executor, database);
        } finally {
            // Stop taking new tasks and let submitted tasks finish.
            executor.shutdown();
        }
    }

    /**
     * Accepts connections and hands each socket to one executor task.
     *
     * @param server the listener shared by all workers
     * @param executor the worker pool
     * @param database the shared database
     * @throws IOException if accepting fails while the listener is still open
     */
    // Socket ownership is transferred to the worker, which closes it.
    @SuppressWarnings("PMD.CloseResource")
    static void acceptConnections(final ServerSocket server,
            final ExecutorService executor, final Database database)
            throws IOException {
        try {
            while (!server.isClosed()) {
                // accept waits until a client connects and returns its socket.
                final Socket client = server.accept();
                // The lambda describes the task. A worker runs it while the
                // main thread goes back to accept the next connection.
                executor.execute(() -> handleClient(client, database, server));
            }
        } catch (SocketException exception) {
            // Closing the listener for exit also wakes up accept().
            if (!server.isClosed()) {
                // A failure with the listener still open must be reported.
                throw exception;
            }
        }
    }

    /**
     * Reads one request, sends one response, and closes the client connection.
     *
     * @param client the client socket owned by this worker
     * @param database the shared database
     * @param server the listener to close for an exit request
     */
    // Only an exit request may close the shared listener.
    @SuppressWarnings("PMD.UseTryWithResources")
    static void handleClient(final Socket client,
            final Database database, final ServerSocket server) {
        // The worker owns this socket. Close it and its streams when the
        // request ends, including when reading or writing throws.
        try (client;
             DataInputStream input =
                     new DataInputStream(client.getInputStream());
             DataOutputStream output =
                     new DataOutputStream(client.getOutputStream())) {
            // Set a read timeout so an idle client cannot hold this worker
            // indefinitely without sending data.
            client.setSoTimeout(READ_TIMEOUT_MILLIS);
            // readUTF reads one message. readRequest parses its JSON text.
            final JsonObject request = readRequest(input.readUTF());
            // The type field selects the operation, such as get or exit.
            final String command = readString(request.get("type"));
            final boolean stopServer = EXIT.equals(command);
            // Exit only needs an OK reply. Data commands use the database.
            final JsonObject response = stopServer
                    ? ok() : runDatabaseCommand(database, request, command);
            try {
                // Convert the response to text and send it as one message.
                output.writeUTF(response.toString());
                output.flush();
            } finally {
                // After attempting the reply, exit closes the listener
                // even if sending failed. This wakes the thread in accept.
                if (stopServer) {
                    server.close();
                }
            }
        } catch (IOException exception) {
            // Report the failure of this connection from the worker.
            LOGGER.log(Level.WARNING, "Client connection failed.", exception);
        }
    }

    /**
     * Reads one request object from its JSON text.
     *
     * @param requestText the text sent by the client
     * @return the request object, or an empty object for invalid input
     */
    private static JsonObject readRequest(final String requestText) {
        try {
            // Gson can parse several JSON types. Requests need an object
            // with named fields, so an array or scalar becomes empty here.
            final JsonElement parsed = JsonParser.parseString(requestText);
            return parsed.isJsonObject()
                    ? parsed.getAsJsonObject() : new JsonObject();
        } catch (JsonParseException exception) {
            // An empty object has no type, so validation returns an error.
            return new JsonObject();
        }
    }

    /**
     * Calls the database and builds the response for a data command.
     *
     * @param database the shared database
     * @param request the client request
     * @param command the requested operation
     * @return the JSON response to send to the client
     */
    private static JsonObject runDatabaseCommand(final Database database,
            final JsonObject request, final String command) {
        // A key can name one property or list the steps to a nested one.
        final List<String> path = readPath(request.get("key"));
        if (command == null || path.isEmpty()) {
            return error(INVALID_REQUEST);
        }

        try {
            // This switch produces the JsonObject returned to handleClient.
            // yield supplies the result of a case with multiple statements.
            return switch (command) {
                case "set" -> {
                    // A JSON null value can be stored. A missing value cannot.
                    final JsonElement value = request.get("value");
                    if (value == null) {
                        yield error(INVALID_REQUEST);
                    }
                    yield database.set(path, value)
                            ? ok() : error("Invalid path");
                }
                case "get" -> {
                    // An empty Optional means the property does not exist.
                    final JsonElement value = database.get(path).orElse(null);
                    if (value == null) {
                        yield error(NO_SUCH_KEY);
                    }
                    final JsonObject response = ok();
                    // add keeps the original JSON type, including arrays.
                    response.add("value", value);
                    yield response;
                }
                case "delete" -> database.delete(path)
                        ? ok() : error(NO_SUCH_KEY);
                default -> error(INVALID_REQUEST);
            };
        } catch (IOException exception) {
            // Give the client a JSON error and keep details in the server log.
            LOGGER.log(Level.WARNING, "Database I/O error.", exception);
            return error("Database I/O error");
        }
    }

    /**
     * Normalizes a string key or an array of strings to a property path.
     *
     * @param key the JSON key from the client
     * @return an immutable path, or an empty list for an invalid key
     */
    private static List<String> readPath(final JsonElement key) {
        // Turn "table" into a one-step path just like ["table"].
        final String singleKey = readString(key);
        if (singleKey != null) {
            return List.of(singleKey);
        }
        if (key == null || !key.isJsonArray()) {
            return List.of();
        }

        // Each array item must name a property. Preserve the given order.
        final List<String> segments = new ArrayList<>();
        for (final JsonElement item : key.getAsJsonArray()) {
            final String segment = readString(item);
            if (segment == null) {
                return List.of();
            }
            segments.add(segment);
        }
        // The database gets a read-only copy of the path.
        return List.copyOf(segments);
    }

    /**
     * Reads a JSON string without converting numbers or booleans.
     *
     * @param value the JSON element to inspect
     * @return the string value, or null if the element is not a string
     */
    private static String readString(final JsonElement value) {
        // Check the JSON type before getAsString, which can convert
        // numeric and boolean primitives to text as well.
        if (value == null || !value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isString()) {
            return null;
        }
        return value.getAsString();
    }

    /**
     * Creates a successful response.
     *
     * @return a response with status OK
     */
    private static JsonObject ok() {
        final JsonObject response = new JsonObject();
        response.addProperty("response", "OK");
        return response;
    }

    /**
     * Creates an error response.
     *
     * @param reason the protocol error message
     * @return a response with status ERROR and its reason
     */
    private static JsonObject error(final String reason) {
        final JsonObject response = new JsonObject();
        response.addProperty("response", "ERROR");
        response.addProperty("reason", reason);
        return response;
    }
}
