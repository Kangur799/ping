package com.pingroom;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Cienki klient WebSocket do serwera-relaya. Uzywa wbudowanego w Jave java.net.http,
 * wiec nie trzeba zadnych dodatkowych bibliotek.
 * UWAGA: metody Handler sa wolane z watku sieciowego - RoomManager przenosi je na watek gry.
 */
public final class RelayClient implements WebSocket.Listener {

    public interface Handler {
        void onOpen(RelayClient client);

        void onMessage(RelayClient client, JsonObject message);

        void onClosed(RelayClient client, String reason);
    }

    private static final HttpClient HTTP = HttpClient.newHttpClient();
    private static final int MAX_MESSAGE_CHARS = 16_384;

    private final Handler handler;
    private final StringBuilder partial = new StringBuilder();
    private volatile WebSocket socket;
    private volatile boolean closedNotified;
    private CompletableFuture<?> sendChain = CompletableFuture.completedFuture(null);

    public RelayClient(Handler handler) {
        this.handler = handler;
    }

    public void connect(String url) {
        try {
            HTTP.newWebSocketBuilder()
                    .connectTimeout(Duration.ofSeconds(8))
                    .buildAsync(URI.create(url), this)
                    .exceptionally(ex -> {
                        notifyClosed("nie można połączyć z relayem (" + rootMessage(ex) + ")");
                        return null;
                    });
        } catch (IllegalArgumentException e) {
            notifyClosed("nieprawidłowy adres relaya: " + url);
        }
    }

    public boolean isOpen() {
        WebSocket s = socket;
        return s != null && !s.isInputClosed() && !s.isOutputClosed();
    }

    /** Wysylka jest kolejkowana - java.net.http nie pozwala wysylac kilku ramek naraz. */
    public synchronized void send(JsonObject obj) {
        WebSocket s = socket;
        if (s == null) return;
        String text = obj.toString();
        sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> s.sendText(text, true));
    }

    /** Zamyka polaczenie po wyslaniu wszystkiego, co jest w kolejce. */
    public synchronized void close() {
        WebSocket s = socket;
        if (s == null) return;
        socket = null;
        sendChain = sendChain.handle((v, e) -> null).thenCompose(v -> s.sendClose(WebSocket.NORMAL_CLOSURE, "bye"));
    }

    // ---- WebSocket.Listener ----

    @Override
    public void onOpen(WebSocket ws) {
        socket = ws;
        ws.request(1);
        handler.onOpen(this);
    }

    @Override
    public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
        partial.append(data);
        if (partial.length() > MAX_MESSAGE_CHARS) {
            partial.setLength(0);
        } else if (last) {
            String text = partial.toString();
            partial.setLength(0);
            try {
                JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
                handler.onMessage(this, obj);
            } catch (RuntimeException ignored) {
                // zepsuta wiadomość - ignorujemy
            }
        }
        ws.request(1);
        return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
        socket = null;
        notifyClosed(reason == null || reason.isBlank() ? "połączenie zamknięte (" + statusCode + ")" : reason);
        return null;
    }

    @Override
    public void onError(WebSocket ws, Throwable error) {
        socket = null;
        notifyClosed("błąd połączenia: " + rootMessage(error));
    }

    private void notifyClosed(String reason) {
        if (closedNotified) return;
        closedNotified = true;
        handler.onClosed(this, reason);
    }

    private static String rootMessage(Throwable t) {
        Throwable root = t;
        while (root.getCause() != null && root.getCause() != root) root = root.getCause();
        String m = root.getMessage();
        return m == null ? root.getClass().getSimpleName() : m;
    }
}
