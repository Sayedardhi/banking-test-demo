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

package anthos.samples.bankofanthos.ledgerwriter;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Controlled HTTP substitute for balancereader (GET /balances/{account}), outside the ledgerwriter test scope.
 * Records each lookup, can return an error status, be taken offline, or hold lookups until N are in flight.
 */
final class BalanceReaderStub {

    record Lookup(String path, String authorization) {
    }

    private final Map<String, Integer> balances = new ConcurrentHashMap<>();
    private final List<Lookup> lookups = new CopyOnWriteArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private volatile int status = 200;
    private volatile CountDownLatch gate;
    private HttpServer server;
    private int port;

    synchronized void start() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/balances/", this::handle);
        server.setExecutor(executor);
        server.start();
        port = server.getAddress().getPort();
    }

    synchronized void stop() {
        server.stop(0);
    }

    String address() {
        return "127.0.0.1:" + port;
    }

    void reset() {
        balances.clear();
        lookups.clear();
        status = 200;
        gate = null;
    }

    void balance(String account, int cents) {
        balances.put(account, cents);
    }

    void respondWith(int httpStatus) {
        status = httpStatus;
    }

    /** Hold every lookup until {@code parties} lookups are in flight (or 5 s pass), then answer them all. */
    void holdUntilConcurrent(int parties) {
        gate = new CountDownLatch(parties);
    }

    List<Lookup> lookups() {
        return List.copyOf(lookups);
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        lookups.add(new Lookup(path, exchange.getRequestHeaders().getFirst("Authorization")));
        CountDownLatch held = gate;
        if (held != null) {
            held.countDown();
            try {
                held.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (status != 200) {
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
            return;
        }
        byte[] body = String.valueOf(balances.getOrDefault(path.substring("/balances/".length()), 0))
                .getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
