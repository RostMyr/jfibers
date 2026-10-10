package com.github.rostmyr.jfibers.benchmarks;

import com.sun.management.OperatingSystemMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** An isolated loopback server; its CPU is never included in the client process counter. */
public final class HttpTestServer {
    private final int delayMillis;
    private final String secondPath = "/second?token=" + Arrays.hashCode(HttpSupport.PAYLOAD);
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peakActive = new AtomicInteger();

    private HttpTestServer(int delayMillis) {
        this.delayMillis = delayMillis;
    }

    public static void main(String[] args) throws Exception {
        int delay = Integer.parseInt(args[0]);
        if (delay < 0 || delay > 1000) {
            throw new IllegalArgumentException("Server delay must be between 0 and 1000 ms");
        }
        HttpTestServer workload = new HttpTestServer(delay);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(executor);
            server.createContext("/", workload::handle);
            server.start();
            try {
                System.out.println(HttpSupport.json(Map.of("ready", true, "port", server.getAddress().getPort())));
                BufferedReader commands = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
                String command;
                while ((command = commands.readLine()) != null && !command.equals("stop")) {
                    if (!command.equals("stats")) {
                        throw new IllegalArgumentException("Unknown server command: " + command);
                    }
                    System.out.println(HttpSupport.json(workload.snapshot()));
                }
            } finally {
                server.stop(0);
            }
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        requests.incrementAndGet();
        int concurrent = active.incrementAndGet();
        peakActive.accumulateAndGet(concurrent, Math::max);
        try {
            String path = exchange.getRequestURI().toString();
            if (!exchange.getRequestMethod().equals("GET") || !(path.equals("/first") || path.equals(secondPath))) {
                failures.incrementAndGet();
                exchange.sendResponseHeaders(400, -1);
                return;
            }
            if (delayMillis != 0) {
                Thread.sleep(delayMillis);
            }
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(200, HttpSupport.PAYLOAD.length);
            exchange.getResponseBody().write(HttpSupport.PAYLOAD);
            bytes.addAndGet(HttpSupport.PAYLOAD.length);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            failures.incrementAndGet();
        } catch (IOException failure) {
            failures.incrementAndGet();
            throw failure;
        } finally {
            exchange.close();
            active.decrementAndGet();
        }
    }

    private Map<String, Object> snapshot() {
        OperatingSystemMXBean os = (OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("wall_nanos", System.nanoTime());
        result.put("cpu_nanos", os.getProcessCpuTime());
        result.put("requests", requests.get());
        result.put("failures", failures.get());
        result.put("body_bytes", bytes.get());
        result.put("active", active.get());
        result.put("peak_active", peakActive.getAndSet(active.get()));
        return result;
    }
}
