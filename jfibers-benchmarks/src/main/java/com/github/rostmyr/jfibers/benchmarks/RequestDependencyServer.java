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
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Separate HTTP dependency whose delayed responses do not occupy handler or timer threads. */
public final class RequestDependencyServer implements AutoCloseable {
    static final int RESPONSE_BYTES = 3 * Long.BYTES;
    private static final int HTTP_QUEUE_CAPACITY = 1024;
    private final Object lifecycle = new Object();
    private final int capacity;
    private final Semaphore admissions;
    private final HttpServer server;
    private final ThreadPoolExecutor handlers = executor("request-dependency-handler-");
    private final ThreadPoolExecutor writers = executor("request-dependency-writer-");
    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(1,
        Thread.ofPlatform().name("request-dependency-timer-", 0).factory());
    private final Set<Entry> entries = ConcurrentHashMap.newKeySet();
    private final AtomicLong requests = new AtomicLong();
    private final AtomicLong responses = new AtomicLong();
    private final AtomicLong bytes = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final AtomicLong responseErrors = new AtomicLong();
    private final AtomicInteger active = new AtomicInteger();
    private final AtomicInteger peakActive = new AtomicInteger();
    private final AtomicInteger activeAdmitted = new AtomicInteger();
    private final AtomicInteger peakAdmitted = new AtomicInteger();
    private final AtomicInteger pendingTimers = new AtomicInteger();
    private final Map<Integer, AtomicLong> statuses = Map.of(
        200, new AtomicLong(), 400, new AtomicLong(), 503, new AtomicLong());
    private boolean closed; // guarded by lifecycle

    RequestDependencyServer(int capacity) throws IOException {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        admissions = new Semaphore(capacity);
        timers.setRemoveOnCancelPolicy(true);
        timers.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), HTTP_QUEUE_CAPACITY);
        server.setExecutor(handlers);
        server.createContext("/", this::handle);
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 1) {
            throw new IllegalArgumentException("[capacity]");
        }
        try (RequestDependencyServer application = new RequestDependencyServer(
            args.length == 0 ? 1024 : Integer.parseInt(args[0]))) {
            application.start();
            System.out.println(HttpSupport.json(Map.of("ready", true, "port", application.port())));
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            String command;
            while ((command = input.readLine()) != null && !command.equals("stop")) {
                if (!command.equals("stats")) {
                    throw new IllegalArgumentException("Unknown dependency command: " + command);
                }
                System.out.println(HttpSupport.json(application.snapshot()));
            }
        }
    }

    void start() {
        synchronized (lifecycle) {
            if (closed) {
                throw new IllegalStateException("Dependency is closed");
            }
            server.start();
        }
    }

    int port() {
        return server.getAddress().getPort();
    }

    private static ThreadPoolExecutor executor(String name) {
        return new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(HTTP_QUEUE_CAPACITY), Thread.ofPlatform().name(name, 0).factory(),
            new ThreadPoolExecutor.AbortPolicy());
    }

    private void handle(HttpExchange exchange) throws IOException {
        if (!exchange.getRequestURI().getPath().equals("/step")) {
            try (exchange) {
                boolean stats = exchange.getRequestMethod().equals("GET")
                    && exchange.getRequestURI().getPath().equals("/stats");
                if (!stats) {
                    errors.incrementAndGet();
                }
                byte[] body = HttpSupport.json(stats ? snapshot() : Map.of("error", "Unsupported route"))
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(stats ? 200 : 400, body.length);
                exchange.getResponseBody().write(body);
            }
            return;
        }

        Entry entry = new Entry(exchange);
        synchronized (lifecycle) {
            if (closed) {
                exchange.close();
                return;
            }
            entries.add(entry);
            requests.incrementAndGet();
            peakActive.accumulateAndGet(active.incrementAndGet(), Math::max);
        }

        long id;
        int step;
        int delay;
        try {
            Map<String, String> parameters = query(exchange.getRequestURI().getRawQuery());
            id = Long.parseLong(parameters.get("id"));
            step = Integer.parseInt(parameters.get("step"));
            delay = Integer.parseInt(parameters.get("delayMs"));
            if (!exchange.getRequestMethod().equals("GET") || parameters.size() != 3
                || step < 0 || step > 2 || delay < 0 || delay > 1000) {
                throw new IllegalArgumentException("Invalid step parameters");
            }
        } catch (IllegalArgumentException invalid) {
            errors.incrementAndGet();
            respond(entry, 400, 0, -1);
            return;
        }

        synchronized (entry) {
            if (entry.finished) {
                return;
            }
            if (!admissions.tryAcquire()) {
                respond(entry, 503, id, step);
                return;
            }
            entry.admitted = true;
            peakAdmitted.accumulateAndGet(activeAdmitted.incrementAndGet(), Math::max);
            // Registration holds the entry lock so even a zero-delay callback sees its timer handle.
            entry.timerPending = true;
            pendingTimers.incrementAndGet();
            try {
                entry.timer = timers.schedule(() -> respond(entry, 200, id, step), delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException rejected) {
                errors.incrementAndGet();
                responseErrors.incrementAndGet();
                entry.finish();
            }
        }
    }

    private void respond(Entry entry, int status, long id, int step) {
        synchronized (entry) {
            if (entry.finished || entry.responseQueued) {
                return;
            }
            entry.responseQueued = true;
            if (entry.timerPending) {
                entry.timerPending = false;
                entry.timer = null;
                pendingTimers.decrementAndGet();
            }
        }
        try {
            writers.execute(() -> write(entry, status, id, step));
        } catch (RejectedExecutionException rejected) {
            // Writer saturation invalidates the run; it is never counted as useful dependency work.
            responseErrors.incrementAndGet();
            entry.finish();
        }
    }

    private void write(Entry entry, int status, long id, int step) {
        HttpExchange exchange = entry.exchange;
        try {
            byte[] body = ByteBuffer.allocate(RESPONSE_BYTES).putLong(id).putLong(step).putLong(42).array();
            exchange.getResponseHeaders().set("Content-Type", "application/octet-stream");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.getResponseBody().close();
            bytes.addAndGet(body.length);
            statuses.get(status).incrementAndGet();
            responses.incrementAndGet();
        } catch (IOException | RuntimeException failure) {
            responseErrors.incrementAndGet();
        } finally {
            entry.finish();
        }
    }

    private static Map<String, String> query(String raw) {
        Map<String, String> result = new HashMap<>();
        if (raw != null) {
            for (String item : raw.split("&")) {
                String[] pair = item.split("=", 2);
                if (pair.length != 2 || result.putIfAbsent(pair[0], pair[1]) != null) {
                    throw new IllegalArgumentException("Invalid query");
                }
            }
        }
        return result;
    }

    Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("capacity", capacity);
        result.put("requests", requests.get());
        result.put("responses", responses.get());
        result.put("response_bytes", bytes.get());
        for (var status : statuses.entrySet()) {
            result.put("status_" + status.getKey(), status.getValue().get());
        }
        result.put("errors", errors.get());
        result.put("response_errors", responseErrors.get());
        result.put("active_exchanges", active.get());
        result.put("peak_active", peakActive.get());
        result.put("active_admitted", activeAdmitted.get());
        result.put("peak_admitted", peakAdmitted.get());
        result.put("pending_timers", pendingTimers.get());
        result.put("timer_queue_size", timers.getQueue().size());
        result.put("response_queue", writers.getQueue().size());
        result.put("response_active", writers.getActiveCount());
        result.put("handler_queue", handlers.getQueue().size());
        result.put("handler_active", handlers.getActiveCount());
        result.put("snapshot_nanos", System.nanoTime());
        result.put("cpu_nanos", ((OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean()).getProcessCpuTime());
        result.put("allocated_bytes", ((ThreadMXBean) ManagementFactory.getThreadMXBean()).getTotalThreadAllocatedBytes());
        result.put("gc_count", ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> Math.max(0, gc.getCollectionCount())).sum());
        result.put("gc_millis", ManagementFactory.getGarbageCollectorMXBeans().stream()
            .mapToLong(gc -> Math.max(0, gc.getCollectionTime())).sum());
        return result;
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
        }
        server.stop(0);
        handlers.shutdownNow();
        timers.shutdownNow();
        writers.shutdownNow();
        entries.forEach(Entry::finish);
        try {
            if (!handlers.awaitTermination(5, TimeUnit.SECONDS)
                || !timers.awaitTermination(5, TimeUnit.SECONDS)
                || !writers.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Dependency executors did not terminate");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing dependency", interrupted);
        }
    }

    private final class Entry {
        private final HttpExchange exchange;
        private ScheduledFuture<?> timer;
        private boolean timerPending;
        private boolean responseQueued;
        private boolean admitted;
        private boolean finished;

        Entry(HttpExchange exchange) {
            this.exchange = exchange;
        }

        synchronized void finish() {
            if (finished) {
                return;
            }
            finished = true;
            if (timerPending) {
                timerPending = false;
                if (timer != null) {
                    timer.cancel(false);
                    timer = null;
                }
                pendingTimers.decrementAndGet();
            }
            exchange.close();
            entries.remove(this);
            active.decrementAndGet();
            if (admitted) {
                activeAdmitted.decrementAndGet();
                admissions.release();
            }
        }
    }
}
