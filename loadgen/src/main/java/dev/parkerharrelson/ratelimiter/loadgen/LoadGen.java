package dev.parkerharrelson.ratelimiter.loadgen;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Open-loop HTTP load generator. Requests are sent at a fixed arrival rate regardless of how fast
 * responses come back, which is what a population of independent clients looks like and what a
 * rate limiter has to cope with. One virtual thread per in-flight request.
 *
 * <pre>
 *   steady    constant aggregate RPS spread across users and targets. Shows whether the enforced
 *             rate matches the configured limit and what the limiter costs in latency.
 *   boundary  quiet, then a burst just before a window boundary and another just after it. Shows
 *             the fixed-window 2x problem and how the other algorithms handle the same traffic.
 * </pre>
 *
 * Output: a markdown summary on stdout (paste-able into docs), optional per-second CSV
 * ({@code -out}) and JSON summary ({@code -json}).
 */
public final class LoadGen {

    record Result(Instant at, Duration latency, int status, String policy, String instance, boolean error) {
    }

    record Options(List<String> targets, int users, double rps, Duration duration, String path, String mode,
                   Duration period, int burst, int cycles, String out, String json, Duration timeout, String label) {
    }

    private final Options o;
    private final HttpClient client;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final ConcurrentLinkedQueue<Result> results = new ConcurrentLinkedQueue<>();

    private LoadGen(Options o) {
        this.o = o;
        this.client = HttpClient.newBuilder()
                .connectTimeout(o.timeout())
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();
    }

    public static void main(String[] args) throws Exception {
        Options o = parse(args);
        LoadGen gen = new LoadGen(o);
        Instant start = Instant.now();
        switch (o.mode()) {
            case "steady" -> gen.runSteady();
            case "boundary" -> gen.runBoundary();
            default -> {
                System.err.println("unknown mode '" + o.mode() + "' (want steady|boundary)");
                System.exit(2);
            }
        }
        gen.report(start);
        System.exit(0);
    }

    // ------------------------------------------------------------------ modes

    private void runSteady() throws InterruptedException {
        System.err.printf("steady: %.0f rps for %s across %d users and %d targets -> %s%n",
                o.rps(), o.duration(), o.users(), o.targets().size(), o.path());
        long intervalNanos = (long) (1_000_000_000.0 / o.rps());
        long end = System.nanoTime() + o.duration().toNanos();
        long next = System.nanoTime();
        int i = 0;
        List<java.util.concurrent.Future<?>> inflight = new ArrayList<>();
        while (System.nanoTime() < end) {
            long wait = next - System.nanoTime();
            if (wait > 0) {
                LockSupport.parkNanos(wait);
            }
            next += intervalNanos;
            String user = "user-" + ThreadLocalRandom.current().nextInt(o.users());
            String target = o.targets().get(i % o.targets().size());
            inflight.add(workers.submit(() -> fire(target, user)));
            i++;
        }
        awaitAll(inflight);
    }

    /**
     * Waits for the next wall-clock boundary of {@code period}, then sends {@code burst} requests per
     * user 200ms before it and again 200ms after. Window algorithms align to absolute time
     * ({@code floor(now / period)}), so the generator can predict exactly where the boundary falls.
     */
    private void runBoundary() throws InterruptedException {
        System.err.printf("boundary: %d cycles of %d-request bursts per user (x%d users) straddling %s boundaries -> %s%n",
                o.cycles(), o.burst(), o.users(), o.period(), o.path());
        Duration lead = Duration.ofMillis(200);
        for (int c = 0; c < o.cycles(); c++) {
            Instant now = Instant.now();
            long periodMs = o.period().toMillis();
            Instant next = Instant.ofEpochMilli((now.toEpochMilli() / periodMs + 1) * periodMs);
            if (Duration.between(now, next).compareTo(lead.plusMillis(500)) < 0) {
                next = next.plus(o.period());
            }
            System.err.printf("  cycle %d: boundary at %s (in %d ms)%n", c + 1,
                    LocalTime.ofInstant(next, ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")),
                    Duration.between(now, next).toMillis());
            sleepUntil(next.minus(lead));
            burst();
            sleepUntil(next.plus(lead));
            burst();
        }
    }

    private void burst() throws InterruptedException {
        List<java.util.concurrent.Future<?>> inflight = new ArrayList<>();
        for (int u = 0; u < o.users(); u++) {
            String user = "user-" + u;
            for (int i = 0; i < o.burst(); i++) {
                String target = o.targets().get(i % o.targets().size());
                inflight.add(workers.submit(() -> fire(target, user)));
            }
        }
        awaitAll(inflight);
    }

    private void fire(String target, String user) {
        HttpRequest req = HttpRequest.newBuilder(URI.create(target + o.path()))
                .header("X-User-ID", user)
                .timeout(o.timeout())
                .GET()
                .build();
        Instant t0 = Instant.now();
        long n0 = System.nanoTime();
        try {
            HttpResponse<Void> resp = client.send(req, HttpResponse.BodyHandlers.discarding());
            results.add(new Result(t0, Duration.ofNanos(System.nanoTime() - n0), resp.statusCode(),
                    resp.headers().firstValue("X-RateLimit-Policy").orElse(""),
                    resp.headers().firstValue("X-Instance").orElse(""), false));
        } catch (IOException | InterruptedException e) {
            results.add(new Result(t0, Duration.ofNanos(System.nanoTime() - n0), 0, "", "", true));
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void awaitAll(List<java.util.concurrent.Future<?>> futures) throws InterruptedException {
        for (var f : futures) {
            try {
                f.get();
            } catch (java.util.concurrent.ExecutionException e) {
                // fire() records its own errors
            }
        }
    }

    private static void sleepUntil(Instant t) throws InterruptedException {
        long ms = Duration.between(Instant.now(), t).toMillis();
        if (ms > 0) {
            Thread.sleep(ms);
        }
    }

    // ------------------------------------------------------------------ reporting

    record BoundaryStat(String boundary, int sent, int beforeAllowed, int afterAllowed) {
    }

    private void report(Instant start) throws IOException {
        List<Result> all = new ArrayList<>(results);
        int total = all.size();
        int allowed = 0;
        int denied = 0;
        int errors = 0;
        int other = 0;
        Map<String, Integer> byInstance = new TreeMap<>();
        Map<String, Integer> byPolicy = new TreeMap<>();
        List<Duration> latencies = new ArrayList<>(total);
        Instant end = start;
        for (Result r : all) {
            if (r.error()) {
                errors++;
            } else if (r.status() == 200) {
                allowed++;
                byInstance.merge(r.instance().isEmpty() ? "?" : r.instance(), 1, Integer::sum);
            } else if (r.status() == 429) {
                denied++;
                byPolicy.merge(r.policy().isEmpty() ? "?" : r.policy(), 1, Integer::sum);
            } else {
                other++;
            }
            latencies.add(r.latency());
            if (r.at().isAfter(end)) {
                end = r.at();
            }
        }
        latencies.sort(Comparator.naturalOrder());
        double durationS = Math.max(Duration.between(start, end).toMillis() / 1000.0, 1.0);
        double allowedRps = allowed / durationS;
        double p50 = ms(percentile(latencies, 0.50));
        double p95 = ms(percentile(latencies, 0.95));
        double p99 = ms(percentile(latencies, 0.99));
        double max = latencies.isEmpty() ? 0 : ms(latencies.getLast());
        List<BoundaryStat> boundaries = o.mode().equals("boundary") ? boundaryStats(all) : List.of();

        StringBuilder md = new StringBuilder("\n");
        if (!o.label().isEmpty()) {
            md.append("### ").append(o.label()).append(" (").append(o.mode()).append(", ").append(o.path()).append(")\n\n");
        }
        md.append("| metric | value |\n|---|---|\n");
        md.append("| requests sent | ").append(total).append(" |\n");
        md.append("| allowed (200) | ").append(allowed).append(" |\n");
        md.append("| denied (429) | ").append(denied).append(" |\n");
        md.append("| errors / other | ").append(errors).append(" / ").append(other).append(" |\n");
        if (o.mode().equals("steady")) {
            md.append(String.format("| offered rps | %.1f |%n", o.rps()));
            md.append(String.format("| allowed rps | %.1f |%n", allowedRps));
        }
        md.append(String.format("| latency p50 / p95 / p99 / max (ms) | %.2f / %.2f / %.2f / %.2f |%n", p50, p95, p99, max));
        md.append("| allowed by instance | ").append(fmt(byInstance)).append(" |\n");
        if (!byPolicy.isEmpty()) {
            md.append("| denied by policy | ").append(fmt(byPolicy)).append(" |\n");
        }
        if (!boundaries.isEmpty()) {
            md.append("\n| boundary | sent each side | allowed before | allowed after | allowed within ~400ms |\n|---|---|---|---|---|\n");
            for (BoundaryStat b : boundaries) {
                md.append(String.format("| %s | %d | %d | %d | **%d** |%n", b.boundary(), b.sent(), b.beforeAllowed(), b.afterAllowed(),
                        b.beforeAllowed() + b.afterAllowed()));
            }
        }
        System.out.println(md);

        if (!o.out().isEmpty()) {
            writeCsv(Path.of(o.out()), start, all);
        }
        if (!o.json().isEmpty()) {
            Map<String, Object> j = new LinkedHashMap<>();
            j.put("label", o.label());
            j.put("mode", o.mode());
            j.put("path", o.path());
            j.put("targets", o.targets().size());
            j.put("users", o.users());
            j.put("offered_rps", o.mode().equals("steady") ? o.rps() : 0);
            j.put("duration_s", durationS);
            j.put("total", total);
            j.put("allowed", allowed);
            j.put("denied", denied);
            j.put("errors", errors);
            j.put("other_status", other);
            j.put("allowed_rps", allowedRps);
            j.put("latency_p50_ms", p50);
            j.put("latency_p95_ms", p95);
            j.put("latency_p99_ms", p99);
            j.put("latency_max_ms", max);
            j.put("by_instance", byInstance);
            j.put("denied_by_policy", byPolicy);
            List<Map<String, Object>> bs = new ArrayList<>();
            for (BoundaryStat b : boundaries) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("boundary", b.boundary());
                m.put("sent_each_side", b.sent());
                m.put("before_allowed", b.beforeAllowed());
                m.put("after_allowed", b.afterAllowed());
                bs.add(m);
            }
            j.put("boundaries", bs);
            Files.writeString(Path.of(o.json()), Json.write(j));
        }
    }

    private List<BoundaryStat> boundaryStats(List<Result> all) {
        long periodMs = o.period().toMillis();
        TreeMap<Long, int[]> byBoundary = new TreeMap<>(); // boundaryMs -> {before, after}
        for (Result r : all) {
            long ms = r.at().toEpochMilli();
            long b = (ms / periodMs) * periodMs;
            if (ms - b > periodMs / 2) {
                b += periodMs; // sent before the boundary
            }
            int[] counts = byBoundary.computeIfAbsent(b, k -> new int[2]);
            if (r.status() == 200) {
                counts[ms < b ? 0 : 1]++;
            }
        }
        List<BoundaryStat> out = new ArrayList<>();
        for (var e : byBoundary.entrySet()) {
            String label = LocalTime.ofInstant(Instant.ofEpochMilli(e.getKey()), ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm:ss"));
            out.add(new BoundaryStat(label, o.users() * o.burst(), e.getValue()[0], e.getValue()[1]));
        }
        return out;
    }

    private static void writeCsv(Path path, Instant start, List<Result> all) throws IOException {
        TreeMap<Long, List<Result>> bySecond = new TreeMap<>();
        for (Result r : all) {
            bySecond.computeIfAbsent(Duration.between(start, r.at()).toSeconds(), k -> new ArrayList<>()).add(r);
        }
        long maxSec = bySecond.isEmpty() ? 0 : bySecond.lastKey();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(path))) {
            w.println("second,allowed,denied,errors,p50_ms,p99_ms");
            for (long s = 0; s <= maxSec; s++) {
                List<Result> rs = bySecond.getOrDefault(s, List.of());
                int a = 0;
                int d = 0;
                int e = 0;
                List<Duration> lat = new ArrayList<>();
                for (Result r : rs) {
                    if (r.error()) {
                        e++;
                    } else if (r.status() == 200) {
                        a++;
                    } else if (r.status() == 429) {
                        d++;
                    }
                    lat.add(r.latency());
                }
                lat.sort(Comparator.naturalOrder());
                w.printf("%d,%d,%d,%d,%.2f,%.2f%n", s, a, d, e, ms(percentile(lat, .5)), ms(percentile(lat, .99)));
            }
        }
    }

    private static Duration percentile(List<Duration> sorted, double p) {
        if (sorted.isEmpty()) {
            return Duration.ZERO;
        }
        return sorted.get((int) ((sorted.size() - 1) * p));
    }

    private static double ms(Duration d) {
        return d.toNanos() / 1_000_000.0;
    }

    private static String fmt(Map<String, Integer> m) {
        StringBuilder sb = new StringBuilder();
        m.forEach((k, v) -> sb.append(sb.isEmpty() ? "" : ", ").append(k).append('=').append(v));
        return sb.toString();
    }

    // ------------------------------------------------------------------ args

    private static Options parse(String[] args) {
        Map<String, String> a = new LinkedHashMap<>();
        for (int i = 0; i < args.length; i++) {
            String k = args[i];
            if (!k.startsWith("-")) {
                usage("unexpected argument " + k);
            }
            k = k.replaceFirst("^--?", "");
            if (k.equals("help") || k.equals("h")) {
                usage(null);
            }
            if (i + 1 >= args.length) {
                usage("missing value for -" + k);
            }
            a.put(k, args[++i]);
        }
        try {
            return new Options(
                    List.of(a.getOrDefault("targets", "http://localhost:8081,http://localhost:8082,http://localhost:8083").split(",")),
                    Integer.parseInt(a.getOrDefault("users", "5")),
                    Double.parseDouble(a.getOrDefault("rps", "100")),
                    parseDuration(a.getOrDefault("duration", "20s")),
                    a.getOrDefault("path", "/api/cheap"),
                    a.getOrDefault("mode", "steady"),
                    parseDuration(a.getOrDefault("period", "10s")),
                    Integer.parseInt(a.getOrDefault("burst", "20")),
                    Integer.parseInt(a.getOrDefault("cycles", "3")),
                    a.getOrDefault("out", ""),
                    a.getOrDefault("json", ""),
                    parseDuration(a.getOrDefault("timeout", "2s")),
                    a.getOrDefault("label", ""));
        } catch (RuntimeException e) {
            usage(e.getMessage());
            throw e;
        }
    }

    private static Duration parseDuration(String s) {
        s = s.trim();
        if (s.endsWith("ms")) {
            return Duration.ofMillis(Long.parseLong(s.substring(0, s.length() - 2)));
        }
        if (s.endsWith("s")) {
            return Duration.ofSeconds(Long.parseLong(s.substring(0, s.length() - 1)));
        }
        if (s.endsWith("m")) {
            return Duration.ofMinutes(Long.parseLong(s.substring(0, s.length() - 1)));
        }
        return Duration.parse(s);
    }

    private static void usage(String error) {
        if (error != null) {
            System.err.println("error: " + error);
        }
        System.err.println("""
                usage: loadgen [options]
                  -targets URLS   comma-separated base URLs, round-robin (default 3 local replicas)
                  -users N        distinct X-User-ID values user-0..user-N-1 (5)
                  -mode MODE      steady | boundary (steady)
                  -rps R          steady: aggregate requests per second (100)
                  -duration D     steady: how long, e.g. 30s (20s)
                  -period D       boundary: the limit's period; bursts align to it (10s)
                  -burst N        boundary: requests per burst per user (20)
                  -cycles N       boundary: boundaries to test (3)
                  -path P         request path (/api/cheap)
                  -timeout D      per-request timeout (2s)
                  -label S        free text included in the summary (e.g. algorithm)
                  -out FILE       per-second CSV
                  -json FILE      JSON summary
                """);
        System.exit(error == null ? 0 : 2);
    }

    /** Just enough JSON to write the summary without a dependency. */
    static final class Json {
        static String write(Object v) {
            StringBuilder sb = new StringBuilder();
            write(sb, v, 0);
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void write(StringBuilder sb, Object v, int indent) {
            String pad = "  ".repeat(indent + 1);
            String padEnd = "  ".repeat(indent);
            switch (v) {
                case null -> sb.append("null");
                case String s -> sb.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"")).append('"');
                case Number n -> sb.append(n instanceof Double d && (d.isNaN() || d.isInfinite()) ? "0" : n.toString());
                case Boolean b -> sb.append(b);
                case Map<?, ?> m -> {
                    sb.append("{\n");
                    int i = 0;
                    for (var e : ((Map<String, Object>) m).entrySet()) {
                        sb.append(pad).append('"').append(e.getKey()).append("\": ");
                        write(sb, e.getValue(), indent + 1);
                        sb.append(++i < m.size() ? ",\n" : "\n");
                    }
                    sb.append(padEnd).append('}');
                }
                case List<?> l -> {
                    sb.append("[\n");
                    for (int i = 0; i < l.size(); i++) {
                        sb.append(pad);
                        write(sb, l.get(i), indent + 1);
                        sb.append(i + 1 < l.size() ? ",\n" : "\n");
                    }
                    sb.append(padEnd).append(']');
                }
                default -> write(sb, v.toString(), indent);
            }
        }
    }
}
