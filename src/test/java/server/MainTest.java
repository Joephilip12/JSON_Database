package server;

import com.google.gson.JsonNull;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests server commands over in-memory streams without binding a port. */
class MainTest {
    private Database database;

    @BeforeEach
    void setUp() {
        // Use a fresh mock per test to control replies and check calls.
        database = mock(Database.class);
    }

    /** An accepted connection is passed to the executor and receives a reply. */
    @Test
    void delegatesAcceptedClientToExecutor() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        ExecutorService executor = mock(ExecutorService.class);
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        String request = "{\"type\":\"get\",\"key\":\"table\"}";
        Socket client = clientWithRequest(request, reply);
        when(database.get(List.of("table"))).thenReturn(Optional.of(new JsonPrimitive("reply")));
        // Allow one accept, then tell the loop the listener has closed.
        when(listener.isClosed()).thenReturn(false, true);
        when(listener.accept()).thenReturn(client);
        // Run the submitted lambda immediately in the test thread.
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(0).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        Main.acceptConnections(listener, executor, database);

        verify(executor).execute(any(Runnable.class));
        verify(client).close();
        verify(listener, never()).close();
        assertEquals("{\"response\":\"OK\",\"value\":\"reply\"}", readFrame(reply));
    }

    /** An exit reply is written before the listener closes. */
    @Test
    void sendsExitReplyBeforeClosingListener() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        ByteArrayOutputStream reply = new ByteArrayOutputStream();
        String request = "{\"type\":\"exit\"}";
        Socket client = clientWithRequest(request, reply);
        // Inspect the captured bytes at the exact moment close is called.
        doAnswer(invocation -> {
            assertEquals("{\"response\":\"OK\"}", readFrame(reply));
            return null;
        }).when(listener).close();

        Main.handleClient(client, database, listener);

        verify(listener).close();
        verify(client).close();
        // Exit is handled by the server and needs no database operation.
        verifyNoInteractions(database);
    }

    /** Closing a listener while it waits is part of normal shutdown. */
    @Test
    void handlesListenerClosingWhileAccepting() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        when(listener.isClosed()).thenReturn(false, true);
        when(listener.accept()).thenThrow(new SocketException("Closed"));

        Main.acceptConnections(listener, mock(ExecutorService.class), database);

        verify(listener).accept();
    }

    /** An unexpected listener error is still reported to the caller. */
    @Test
    void propagatesUnexpectedAcceptErrors() throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        SocketException failure = new SocketException("Unexpected error");
        when(listener.accept()).thenThrow(failure);

        assertSame(failure, assertThrows(SocketException.class,
                () -> Main.acceptConnections(listener, mock(ExecutorService.class),
                        database)));
    }

    /** A broken connection still releases the client socket. */
    @Test
    void closesClientWhenReadingFails() throws IOException {
        Socket client = mock(Socket.class);
        when(client.getInputStream()).thenThrow(new IOException("Disconnected"));

        Main.handleClient(client, database, mock(ServerSocket.class));

        verify(client).close();
    }

    /** Malformed requests never reach the database. */
    @ParameterizedTest
    @ValueSource(strings = {"{", "[]", "{}", "{\"type\":\"get\"}",
            "{\"type\":1,\"key\":\"table\"}", "{\"type\":\"get\",\"key\":false}",
            "{\"type\":\"get\",\"key\":[]}", "{\"type\":\"get\",\"key\":[\"table\",2]}",
            "{\"type\":\"set\",\"key\":\"table\"}", "{\"type\":\"other\",\"key\":\"table\"}"})
    void rejectsInvalidRequests(String request) throws IOException {
        assertResponse("{\"response\":\"ERROR\",\"reason\":\"Invalid request\"}", request);
        verifyNoInteractions(database);
    }

    /** A set request forwards an immutable path and the original JSON value. */
    @Test
    void forwardsNestedSetAndJsonNull() throws IOException {
        List<String> path = List.of("table", "note");
        when(database.set(path, JsonNull.INSTANCE)).thenReturn(true);

        assertResponse("{\"response\":\"OK\"}",
                "{\"type\":\"set\",\"key\":[\"table\",\"note\"],\"value\":null}");
        verify(database).set(path, JsonNull.INSTANCE);
    }

    /** A failed set returns a protocol error instead of replacing a parent. */
    @Test
    void reportsInvalidPath() throws IOException {
        assertResponse("{\"response\":\"ERROR\",\"reason\":\"Invalid path\"}",
                "{\"type\":\"set\",\"key\":[\"table\",\"note\"],\"value\":true}");
    }

    /** A get response preserves an array containing multiple JSON types. */
    @Test
    void returnsTheOriginalJsonType() throws IOException {
        var value = JsonParser.parseString("[250,false,null]");
        when(database.get(List.of("bill"))).thenReturn(Optional.of(value));

        assertResponse("{\"response\":\"OK\",\"value\":[250,false,null]}",
                "{\"type\":\"get\",\"key\":\"bill\"}");
    }

    /** Missing values are reported consistently by get and delete. */
    @Test
    void reportsMissingKeys() throws IOException {
        when(database.get(List.of("missing"))).thenReturn(Optional.empty());
        String response = "{\"response\":\"ERROR\",\"reason\":\"No such key\"}";

        assertResponse(response, "{\"type\":\"get\",\"key\":\"missing\"}");
        assertResponse(response, "{\"type\":\"delete\",\"key\":\"missing\"}");
    }

    /** A successful delete returns OK. */
    @Test
    void deletesTheRequestedPath() throws IOException {
        List<String> path = List.of("table", "bill");
        when(database.delete(path)).thenReturn(true);

        assertResponse("{\"response\":\"OK\"}",
                "{\"type\":\"delete\",\"key\":[\"table\",\"bill\"]}");
        verify(database).delete(path);
    }

    /** A storage exception becomes a JSON error response. */
    @Test
    void reportsDatabaseFailure() throws IOException {
        when(database.get(List.of("bill"))).thenThrow(new IOException("Disk unavailable"));

        assertResponse("{\"response\":\"ERROR\",\"reason\":\"Database I/O error\"}",
                "{\"type\":\"get\",\"key\":\"bill\"}");
    }

    /** Sends a command through the socket handler and checks its JSON reply. */
    private void assertResponse(String expected, String request) throws IOException {
        ServerSocket listener = mock(ServerSocket.class);
        ByteArrayOutputStream reply = new ByteArrayOutputStream();

        // Feed the real handler a framed request through the fake socket.
        Main.handleClient(clientWithRequest(request, reply), database, listener);

        assertEquals(JsonParser.parseString(expected), JsonParser.parseString(readFrame(reply)));
        verify(listener, never()).close();
    }

    /** Builds a socket mock whose input contains one UTF-framed request. */
    private Socket clientWithRequest(String request, ByteArrayOutputStream reply) throws IOException {
        // Encode the request as bytes so the handler can call readUTF.
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(frame)) {
            output.writeUTF(request);
        }
        Socket socket = mock(Socket.class);
        when(socket.getInputStream()).thenReturn(new ByteArrayInputStream(frame.toByteArray()));
        when(socket.getOutputStream()).thenReturn(reply);
        return socket;
    }

    /** Reads one UTF frame from the bytes captured during a test. */
    private String readFrame(ByteArrayOutputStream bytes) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()))) {
            return input.readUTF();
        }
    }
}
