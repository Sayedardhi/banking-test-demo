/*
 * Copyright 2026 Google LLC.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package anthos.samples.bankofanthos.ledgerwriter.integration;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;

/**
 * Stand-in for the external balancereader service: a real HTTP server
 * serving GET /balances/{account}. It is the only stubbed dependency in the
 * integration tests; PostgreSQL and JWT verification are real.
 */
final class BalanceReaderStub {

    record Call(String account, String authorization) { }

    private record Behaviour(int status, String body, long delayMillis,
                             boolean dropConnection) { }

    private final HttpServer server;
    private final Map<String, Behaviour> behaviours = new ConcurrentHashMap<>();
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    BalanceReaderStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/balances/", this::handle);
        server.start();
    }

    String address() {
        return "127.0.0.1:" + server.getAddress().getPort();
    }

    void balance(String account, int cents) {
        behaviours.put(account,
                new Behaviour(200, Integer.toString(cents), 0, false));
    }

    void slowBalance(String account, int cents, long delayMillis) {
        behaviours.put(account, new Behaviour(200, Integer.toString(cents),
                delayMillis, false));
    }

    void error(String account, int status) {
        behaviours.put(account, new Behaviour(status, "error", 0, false));
    }

    void dropConnection(String account) {
        behaviours.put(account, new Behaviour(0, null, 0, true));
    }

    List<Call> callsFor(String account) {
        return calls.stream().filter(c -> c.account().equals(account)).toList();
    }

    void stop() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String account = exchange.getRequestURI().getPath()
                .substring("/balances/".length());
        calls.add(new Call(account,
                exchange.getRequestHeaders().getFirst("Authorization")));
        Behaviour b = behaviours.getOrDefault(account,
                new Behaviour(404, "unknown account", 0, false));
        if (b.delayMillis() > 0) {
            try {
                Thread.sleep(b.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (b.dropConnection()) {
            exchange.close();
            return;
        }
        byte[] body = b.body().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(b.status(), body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }
}
