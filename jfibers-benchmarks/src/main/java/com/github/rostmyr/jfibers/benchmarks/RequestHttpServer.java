package com.github.rostmyr.jfibers.benchmarks;

import com.sun.management.OperatingSystemMXBean;
import com.sun.management.ThreadMXBean;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Shared HTTP adapter: socket writes never execute on a fiber owner or a business-task thread. */
public final class RequestHttpServer {
    static final int RESPONSE_BYTES = 7 * Long.BYTES;
    private final RequestTaskEngine engine;
    private final ThreadPoolExecutor handlers = executor("request-http-handler-", 4, 1024);
    private final ThreadPoolExecutor writers = executor("request-http-writer-", 4, 1024);
    private final ConcurrentHashMap<Long, Entry> entries = new ConcurrentHashMap<>();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong responses = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong responseErrors = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peakActive = new AtomicInteger();
    private final Map<Integer, AtomicLong> statuses = Map.of(
        200, new AtomicLong(), 409, new AtomicLong(), 503, new AtomicLong(),
        504, new AtomicLong(), 500, new AtomicLong());

    private RequestHttpServer(RequestTaskEngine.Backend backend, int capacity, int heavyIterations) {
        engine = new RequestTaskEngine(backend, capacity, heavyIterations);
    }

    public static void main(String[] args) throws Exception {
        RequestHttpServer application = new RequestHttpServer(RequestTaskEngine.Backend.valueOf(args[0]),
            Integer.parseInt(args[1]), Integer.parseInt(args[2]));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 1024);
        server.setExecutor(application.handlers);
        server.createContext("/", application::handle);
        server.start();
        try {
            System.out.println(HttpSupport.json(Map.of("ready", true, "port", server.getAddress().getPort())));
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String command;
            while ((command = input.readLine()) != null && !command.equals("stop")) {
                if (!command.equals("stats")) {
                    throw new IllegalArgumentException("Unknown server command: " + command);
                }
                System.out.println(HttpSupport.json(application.snapshot()));
            }
        } finally {
            // The supervisor normally stops only after client and server counters prove quiescence.
            server.stop(0);
            application.handlers.close();
            application.engine.close();
            application.writers.close();
        }
    }

    private static ThreadPoolExecutor executor(String name, int threads, int queue) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queue), Thread.ofPlatform().name(name, 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        try {
            Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
            if (path.equals("/stats") && exchange.getRequestMethod().equals("GET")) {
                controlResponse(exchange, 200, snapshot());
            } else if (path.equals("/cancel") && exchange.getRequestMethod().equals("POST")) {
                long id = Long.parseLong(query.get("id"));
                Entry entry = entries.get(id);
                int status = entry == null ? 404 : entry.cancel() ? 202 : 409;
                controlResponse(exchange, status, Map.of("id", id, "status", status));
            } else if (path.equals("/work") && exchange.getRequestMethod().equals("GET")) {
                long id = Long.parseLong(query.get("id"));
                String mode = query.getOrDefault("mode", "normal");
                int deadline = Integer.parseInt(query.getOrDefault("deadlineMs", "100"));
                int cleanup = Integer.parseInt(query.getOrDefault("cleanupMs", "0"));
                if (!(mode.equals("normal") || mode.equals("hold"))
                    || deadline < 1 || deadline > 10000 || cleanup < 0 || cleanup > 1000) {
                    throw new IllegalArgumentException("Invalid work parameters");
                }
                work(exchange, id, mode.equals("hold"), deadline, cleanup);
            } else {
                errors.incrementAndGet();
                controlResponse(exchange, 400, Map.of("error", "Unsupported route or method"));
            }
        } catch (IllegalArgumentException invalid) {
            errors.incrementAndGet();
            controlResponse(exchange, 400, Map.of("error", "Invalid request parameters"));
        }
    }

    private void work(HttpExchange exchange, long id, boolean hold, int deadline, int cleanup) throws IOException {
        Entry entry = new Entry(exchange, id);
        // Cancellation can find the entry as soon as it is published. Keep registration atomic
        // with cancel(), so 202 always acknowledges the engine's actual cancellation decision.
        synchronized (entry) {
            if (entries.putIfAbsent(id, entry) != null) {
                errors.incrementAndGet();
                controlResponse(exchange, 400, Map.of("error", "Duplicate active ID"));
                return;
            }
            requests.incrementAndGet();
            peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                RequestTaskEngine.Submission task = engine.submit(id, hold, deadline, cleanup);
                entry.install(task);
                task.completion().whenComplete((result, failure) -> {
                    if (failure != null || result == null) {
                        errors.incrementAndGet();
                        respond(entry, failed(500, id));
                    } else {
                        respond(entry, result);
                    }
                });
            } catch (RejectedExecutionException rejected) {
                respond(entry, failed(503, id));
            } catch (RuntimeException failure) {
                errors.incrementAndGet();
                respond(entry, failed(500, id));
            }
        }
    }

    private static RequestTaskEngine.Result failed(int status, long id) {
        return new RequestTaskEngine.Result(status, id, 0, -1, -1, 0, 0);
    }

    private void respond(Entry entry, RequestTaskEngine.Result result) {
        if (!entry.finished.compareAndSet(false, true)) {
            errors.incrementAndGet();
            return;
        }
        entries.remove(entry.id, entry);
        try {
            writers.execute(() -> write(entry, result));
        } catch (RejectedExecutionException rejected) {
            // A bounded adapter queue is visible as a failed run, never hidden as business throughput.
            responseErrors.incrementAndGet();
            entry.exchange.close();
            active.decrementAndGet();
        }
    }

    private void write(Entry entry, RequestTaskEngine.Result result) {
        HttpExchange exchange = entry.exchange;
        try {
            byte[] body = ByteBuffer.allocate(RESPONSE_BYTES)
                .putLong(result.status()).putLong(result.id()).putLong(result.checksum())
                .putLong(result.queueNanos()).putLong(result.serviceNanos())
                .putLong(result.cleanupNanos()).putLong(result.computeQueueNanos()).array();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(result.status(), body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
            bytes.addAndGet(body.length);
            statuses.get(result.status()).incrementAndGet();
            responses.incrementAndGet();
        } catch (IOException | RuntimeException failure) {
            responseErrors.incrementAndGet();
        } finally {
            exchange.close();
            active.decrementAndGet();
        }
    }

    private static void controlResponse(HttpExchange exchange, int status, Map<String, Object> value) throws IOException {
        try (exchange) {
            byte[] body = HttpSupport.json(value).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> values = new HashMap<>();
        if (raw != null) {
            for (String item : raw.split("&")) {
                String[] parts = item.split("=", 2);
                if (parts.length != 2 || values.putIfAbsent(parts[0], parts[1]) != null) {
                    throw new IllegalArgumentException("Invalid query");
                }
            }
        }
        return values;
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>(engine.snapshot());
        result.put("requests", requests.get());
        result.put("responses", responses.get());
        result.put("response_bytes", bytes.get());
        for (var entry : statuses.entrySet()) {
            result.put("status_" + entry.getKey(), entry.getValue().get());
        }
        result.put("active_exchanges", active.get());
        result.put("peak_exchanges", peakActive.get());
        result.put("response_queue", writers.getQueue().size());
        result.put("response_active", writers.getActiveCount());
        result.put("response_errors", responseErrors.get());
        result.put("server_errors", errors.get());
        result.put("handler_queue", handlers.getQueue().size());
        result.put("handler_active", handlers.getActiveCount());
        result.put("http_queue_capacity", 1024);
        result.put("snapshot_nanos", System.nanoTime());
        result.put("cpu_nanos", ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime());
        result.put("allocated_bytes", ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes());
        result.put("heap_used_bytes", ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed());
        result.put("gc_count", ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum());
        result.put("gc_millis", ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum());
        return result;
    }

    static final class Entry {
        final HttpExchange exchange;
        final long id;
        final AtomicBoolean finished = new AtomicBoolean();
        private RequestTaskEngine.Submission task; // guarded by this, including registration

        Entry(HttpExchange exchange, long id) {
            this.exchange = exchange;
            this.id = id;
        }

        synchronized void install(RequestTaskEngine.Submission submission) {
            task = submission;
        }

        synchronized boolean cancel() {
            return !finished.get() && task != null && task.cancel();
        }
    }
}
