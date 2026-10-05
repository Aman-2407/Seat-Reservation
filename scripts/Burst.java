import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Usage:  java Burst.java <BASE_URL> [ADMIN_TOKEN]
// Options (put them BEFORE Burst.java):  -Dreqs=20000 -Dusers=4000 -Dseats=2000 -Dinflight=400
//
// Single file on purpose, so it runs with just a JDK and no build step.
// Every scenario creates its OWN show, so results never depend on leftover data.
public class Burst {

    // status = -1 means the request never got an HTTP answer (timeout, connection reset...)
    record Res(int status, String body, String clientError) {}

    static String base;
    static String adminToken;
    static HttpClient http;
    static int failures = 0;

    // global tallies across every scenario
    static final AtomicInteger total201 = new AtomicInteger();
    static final AtomicInteger doubleSold = new AtomicInteger();
    static final Set<String> soldSeats = ConcurrentHashMap.newKeySet();   // "showId:seat" of every 201
    static final Random rnd = new Random();
    // unique per run, so keys and users never collide with what an earlier run left in the DB
    static final String RUN = Long.toString(System.currentTimeMillis(), 36);
    static String u(String s) { return s + "-" + RUN; }   // still starts with "user-" where needed

    // ---------------------------------------------------------------- main

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java [-Dreqs=20000 -Dusers=4000 -Dseats=2000 -Dinflight=400] Burst.java <BASE_URL> [ADMIN_TOKEN]");
            System.exit(2);
        }
        base = args[0].replaceAll("/+$", "");
        adminToken = args.length > 1 ? args[1] : System.getenv().getOrDefault("ADMIN_TOKEN", "admin-secret");

        http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(15))
                .executor(Executors.newVirtualThreadPerTaskExecutor())
                .build();

        waitUntilReady();
        Map<String, Double> before = metrics();

        hotSeatStorm();
        sameUserParallel();
        idempotentRetries();
        spoofedIdentity();
        stampede();

        finalChecks(before);

        System.out.println();
        System.out.println(failures == 0 ? "RESULT: ALL CHECKS PASSED" : "RESULT: " + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---------------------------------------------------------------- scenarios

    static void hotSeatStorm() {
        header("1. Hot-seat storm: 500 different users all grabbing seat A12");
        long show = createShow("burst-hot", List.of("A11", "A12", "A13"));
        Run run = new Run("hot-seat", show);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            final int n = i;
            tasks.add(() -> run.record(reserve(show, "user-hot-" + n, List.of("A12"), u("hot-" + n))));
        }
        fire(tasks, 500);   // all 500 in flight at once

        run.print();
        check("exactly one 201 for A12", run.count("201") == 1, "got " + run.count("201"));
        check("all other 499 got a clean 409 seat-taken", run.count("409 seat-taken") == 499,
                "got " + run.count("409 seat-taken"));
        noServerErrors(run);
        reconcile(run, 1);
    }

    static void sameUserParallel() {
        header("2. One user fires 10 parallel reserves (limit is 4)");
        List<String> seats = new ArrayList<>();
        for (int i = 1; i <= 20; i++) seats.add("P" + i);
        long show = createShow("burst-limit", seats);
        Run run = new Run("per-user-limit", show);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            final int n = i;
            tasks.add(() -> run.record(reserve(show, u("user-par"), List.of("P" + n), u("par-" + n))));
        }
        fire(tasks, 10);

        run.print();
        check("at most 4 seats held by the user", run.confirmedSeats.get() <= 4, "held " + run.confirmedSeats.get());
        check("exactly 4 winners and 6 per-user-limit declines",
                run.count("201") == 4 && run.count("409 per-user-limit") == 6,
                "201=" + run.count("201") + ", per-user-limit=" + run.count("409 per-user-limit"));
        noServerErrors(run);
        reconcile(run, run.confirmedSeats.get());
    }

    static void idempotentRetries() {
        header("3. Idempotency: same key sent 50 times in parallel, then same key with other seats");
        long show = createShow("burst-idem", List.of("I1", "I2", "I3", "I4", "I5"));
        Run run = new Run("idempotency", show);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            tasks.add(() -> run.record(reserve(show, u("user-idem"), List.of("I1"), u("same-key"))));
        }
        fire(tasks, 50);

        check("one 201 and 49 replays (200)", run.count("201") == 1 && run.count("200") == 49,
                "201=" + run.count("201") + ", 200=" + run.count("200"));
        check("every response carried the SAME reservation_id", run.reservationIds.size() == 1,
                "distinct ids: " + run.reservationIds.size());

        Res mismatch = reserve(show, u("user-idem"), List.of("I2"), u("same-key"));
        run.record(mismatch);
        check("same key + different seats gives 409 idempotency-mismatch",
                mismatch.status() == 409 && mismatch.body().contains("idempotency-mismatch"),
                "status " + mismatch.status());

        run.print();
        noServerErrors(run);
        reconcile(run, 1);   // the mismatch request must not have booked anything
    }

    static void spoofedIdentity() {
        header("4. Identity comes from the token: spoofed user_id and foreign cancel");
        long show = createShow("burst-spoof", List.of("Z1", "Z2"));
        Run run = new Run("identity", show);

        // user-a's token, but the body claims to be user-b
        String ua = u("user-a"), ub = u("user-b");
        String json = "{\"seats\":[\"Z1\"],\"idempotency_key\":\"" + u("spoof-1") + "\",\"user_id\":\"" + ub + "\"}";
        Res r = post("/shows/" + show + "/reserve", ua, json);
        run.record(r);
        String owner = find(r.body(), "\"user_id\":\"([^\"]*)\"");
        check("reservation belongs to the token's user, not the body's", r.status() == 201 && ua.equals(owner),
                "status " + r.status() + ", user_id=" + owner);

        String id = find(r.body(), "\"reservation_id\":\"([^\"]*)\"");
        Res foreign = post("/reservations/" + id + "/cancel", ub, null);
        check("another user cannot cancel it (403)", foreign.status() == 403, "status " + foreign.status());
        Res own = post("/reservations/" + id + "/cancel", ua, null);
        check("the owner can cancel it (200)", own.status() == 200, "status " + own.status());

        run.print();
        noServerErrors(run);
        reconcile(run, 0);   // booked once, cancelled once, so nothing confirmed
    }

    static void stampede() {
        int reqs = Integer.getInteger("reqs", 20000);
        int users = Integer.getInteger("users", 4000);
        int seatCount = Integer.getInteger("seats", 2000);
        int inflight = Integer.getInteger("inflight", 400);
        int hot = 20;

        header("5. Stampede: ~" + reqs + " requests, " + users + " users, " + seatCount + " seats (" + hot + " hot), ~10% retries");
        List<String> seats = new ArrayList<>();
        for (int i = 0; i < seatCount; i++) seats.add("S" + i);
        long show = createShow("burst-stampede", seats);
        Run run = new Run("stampede", show);

        List<Runnable> tasks = new ArrayList<>();
        for (int i = 0; i < reqs; i++) {
            String user = u("user-s" + rnd.nextInt(users));
            // half the traffic goes at the 20 hot seats, the rest is spread over the whole hall
            String seat = rnd.nextInt(100) < 50 ? seats.get(rnd.nextInt(hot)) : seats.get(rnd.nextInt(seatCount));
            String key = u("k" + i);
            Runnable t = () -> run.record(reserve(show, user, List.of(seat), key));
            tasks.add(t);
            if (rnd.nextInt(10) == 0) tasks.add(t);   // a client retrying with the very same key
        }
        Collections.shuffle(tasks, rnd);

        // watcher: keeps reading the show during the burst and checks the invariant every time
        AtomicBoolean stop = new AtomicBoolean();
        AtomicInteger polls = new AtomicInteger();
        AtomicInteger bad = new AtomicInteger();
        Thread watcher = Thread.ofVirtual().start(() -> {
            while (!stop.get()) {
                Res r = get("/shows/" + show);
                polls.incrementAndGet();
                int total = num(r.body(), "total_seats");
                int sum = num(r.body(), "available") + num(r.body(), "held") + num(r.body(), "confirmed");
                if (r.status() != 200 || total < 0 || sum != total) bad.incrementAndGet();
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
            }
        });

        long t0 = System.nanoTime();
        fire(tasks, inflight);
        double secs = (System.nanoTime() - t0) / 1e9;
        stop.set(true);
        try { watcher.join(); } catch (InterruptedException ignored) { }

        System.out.printf("  sent %d requests in %.1fs (%.0f req/s)%n", tasks.size(), secs, tasks.size() / secs);
        run.print();
        check("invariant held on all " + polls.get() + " reads DURING the burst", polls.get() > 0 && bad.get() == 0,
                bad.get() + " bad reads out of " + polls.get());
        noServerErrors(run);
        reconcile(run, run.confirmedSeats.get());
    }

    static void finalChecks(Map<String, Double> before) {
        header("Final checks");
        check("no seat was ever confirmed twice (seen from the 201 responses)", doubleSold.get() == 0,
                doubleSold.get() + " double-sold seats");

        Map<String, Double> after = metrics();
        if (after.isEmpty()) {
            System.out.println("  (could not read /actuator/prometheus, skipping metrics checks)");
            return;
        }
        System.out.println("  metric deltas during this run:");
        for (var e : after.entrySet()) {
            if (e.getKey().startsWith("seats_available")) {
                System.out.printf("    %-60s %.0f (now)%n", e.getKey(), e.getValue());
            } else {
                System.out.printf("    %-60s +%.0f%n", e.getKey(), e.getValue() - before.getOrDefault(e.getKey(), 0.0));
            }
        }
        double confirmedDelta = after.getOrDefault("reservations_confirmed_total", 0.0)
                - before.getOrDefault("reservations_confirmed_total", 0.0);
        check("reservations_confirmed_total moved by exactly the number of 201s we saw (" + total201.get() + ")",
                (int) confirmedDelta == total201.get(), "counter moved by " + (int) confirmedDelta
                        + " (only valid if nobody else hit the server meanwhile)");
    }

    // ---------------------------------------------------------------- per-scenario bookkeeping

    static class Run {
        final String name;
        final long showId;
        final ConcurrentHashMap<String, LongAdder> dist = new ConcurrentHashMap<>();
        final AtomicInteger confirmedSeats = new AtomicInteger();
        final Set<String> reservationIds = ConcurrentHashMap.newKeySet();

        Run(String name, long showId) { this.name = name; this.showId = showId; }

        void record(Res r) {
            dist.computeIfAbsent(label(r), k -> new LongAdder()).increment();
            if (r.status() == 201) {
                total201.incrementAndGet();
                for (String seat : seatsOf(r.body())) {
                    confirmedSeats.incrementAndGet();
                    // if the same seat shows up in two different 201s, that is a double-sell
                    if (!soldSeats.add(showId + ":" + seat)) doubleSold.incrementAndGet();
                }
            }
            if (r.status() == 200 || r.status() == 201) {
                String id = find(r.body(), "\"reservation_id\":\"([^\"]*)\"");
                if (id != null) reservationIds.add(id);
            }
        }

        long count(String prefix) {
            long n = 0;
            for (var e : dist.entrySet()) if (e.getKey().startsWith(prefix)) n += e.getValue().sum();
            return n;
        }

        void print() {
            System.out.println("  outcome distribution:");
            new TreeMap<>(dist).forEach((k, v) -> System.out.printf("    %-34s %d%n", k, v.sum()));
        }
    }

    static String label(Res r) {
        if (r.status() < 0) return "client-error (" + r.clientError() + ")";
        if (r.status() >= 500) return "5xx";
        if (r.status() == 201) return "201 confirmed";
        if (r.status() == 200) return "200 replay";
        String e = find(r.body(), "\"error\":\"([^\"]*)\"");
        return r.status() + " " + (e == null ? "other" : e);
    }

    static void noServerErrors(Run run) {
        check(run.name + ": zero 5xx and zero client-side errors",
                run.count("5xx") == 0 && run.count("client-error") == 0,
                "5xx=" + run.count("5xx") + ", client-errors=" + run.count("client-error"));
    }

    // Reads the show back and checks the books balance. expectedConfirmed is what the API
    // SHOULD say, worked out from the responses we got, so this compares two independent views.
    static void reconcile(Run run, int expectedConfirmed) {
        Res r = get("/shows/" + run.showId);
        int total = num(r.body(), "total_seats");
        int av = num(r.body(), "available");
        int held = num(r.body(), "held");
        int conf = num(r.body(), "confirmed");
        check(run.name + ": available + held + confirmed == total_seats",
                total >= 0 && av + held + conf == total, av + " + " + held + " + " + conf + " vs " + total);
        check(run.name + ": confirmed in the API == what the responses said (" + expectedConfirmed + ")",
                conf == expectedConfirmed, "API says " + conf);
    }

    // ---------------------------------------------------------------- http helpers

    static long createShow(String name, List<String> seats) {
        StringBuilder sb = new StringBuilder();
        for (String s : seats) { if (sb.length() > 0) sb.append(','); sb.append('"').append(s).append('"'); }
        String json = "{\"name\":\"" + name + "\",\"seats\":[" + sb + "],\"price_paise\":25000}";
        Res r = post("/shows", adminToken, json);
        if (r.status() != 201)
            throw new IllegalStateException("Could not create show: status " + r.status() + " " + r.body() + " " + r.clientError());
        return Long.parseLong(find(r.body(), "\"id\":(\\d+)"));
    }

    static Res reserve(long show, String user, List<String> seats, String key) {
        StringBuilder sb = new StringBuilder();
        for (String s : seats) { if (sb.length() > 0) sb.append(','); sb.append('"').append(s).append('"'); }
        String json = "{\"seats\":[" + sb + "],\"idempotency_key\":\"" + key + "\"}";
        return post("/shows/" + show + "/reserve", user, json);
    }

    static Res post(String path, String token, String json) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json");
            if (token != null) b.header("Authorization", "Bearer " + token);
            b.POST(json == null ? BodyPublishers.noBody() : BodyPublishers.ofString(json));
            HttpResponse<String> r = http.send(b.build(), BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body(), null);
        } catch (Exception e) {
            return new Res(-1, "", e.getClass().getSimpleName());
        }
    }

    static Res get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET().build();
            HttpResponse<String> r = http.send(req, BodyHandlers.ofString());
            return new Res(r.statusCode(), r.body(), null);
        } catch (Exception e) {
            return new Res(-1, "", e.getClass().getSimpleName());
        }
    }

    // Releases all tasks at the same instant (the latch), but never more than maxInFlight at once (the semaphore).
    static void fire(List<Runnable> tasks, int maxInFlight) {
        Semaphore permits = new Semaphore(maxInFlight);
        CountDownLatch go = new CountDownLatch(1);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> futures = new ArrayList<>();
            for (Runnable t : tasks) {
                futures.add(ex.submit(() -> {
                    go.await();
                    permits.acquire();
                    try { t.run(); } finally { permits.release(); }
                    return null;
                }));
            }
            go.countDown();
            for (Future<?> f : futures) f.get();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static void waitUntilReady() throws InterruptedException {
        // free-tier hosts sleep, so give a cold start up to two minutes
        for (int i = 1; i <= 24; i++) {
            Res r = get("/actuator/health/readiness");
            if (r.status() == 200) { System.out.println("Service is ready: " + base); return; }
            System.out.println("Waiting for service (attempt " + i + ", status " + r.status() + ")...");
            Thread.sleep(5000);
        }
        System.out.println("Service never became ready, giving up.");
        System.exit(2);
    }

    static Map<String, Double> metrics() {
        Map<String, Double> m = new TreeMap<>();
        Res r = get("/actuator/prometheus");
        if (r.status() != 200) return m;
        Matcher mt = Pattern.compile(
                "^(reservations_confirmed_total|reservations_declined_total|reservations_cancelled_total|seats_available)(\\{[^}]*\\})?\\s+(\\S+)$",
                Pattern.MULTILINE).matcher(r.body());
        while (mt.find()) m.put(mt.group(1) + (mt.group(2) == null ? "" : mt.group(2)), Double.parseDouble(mt.group(3)));
        return m;
    }

    // ---------------------------------------------------------------- tiny parsing and printing helpers

    static String find(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text == null ? "" : text);
        return m.find() ? m.group(1) : null;
    }

    static int num(String body, String key) {
        String v = find(body, "\"" + key + "\":(\\d+)");
        return v == null ? -1 : Integer.parseInt(v);
    }

    static List<String> seatsOf(String body) {
        List<String> out = new ArrayList<>();
        String s = find(body, "\"seats\":\\[(.*?)\\]");
        if (s == null || s.isBlank()) return out;
        for (String p : s.split(",")) out.add(p.replace("\"", "").trim());
        return out;
    }

    static void check(String what, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.println("  [" + (ok ? "PASS" : "FAIL") + "] " + what + (ok ? "" : "   <-- " + detail));
    }

    static void header(String t) {
        System.out.println();
        System.out.println("=== " + t + " ===");
    }
}