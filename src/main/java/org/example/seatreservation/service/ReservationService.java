package org.example.seatreservation.service;

import io.micrometer.core.instrument.MeterRegistry;
import org.example.seatreservation.exception.ApiException;
import org.example.seatreservation.model.ReservationResponse;
import org.example.seatreservation.model.ShowRow;
import org.example.seatreservation.repository.ReservationRepository;
import org.example.seatreservation.repository.ShowRepository;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

// Multi-seat behaviour: ALL-OR-NOTHING. If any requested seat is taken, the whole request is
// declined and nothing is kept (the transaction rolls back, including the user's quota count)
@Service
public class ReservationService {
    public record Outcome(ReservationResponse reservationResponse, boolean replay){

    }
    private static final int MAX_ATTEMPTS = 3;
    private static final int MAX_SEATS_PER_REQUEST = 100;

    private final ReservationRepository repo;
    private final ShowRepository shows;
    private final TransactionTemplate tx;
    private final MeterRegistry metrics;

    public ReservationService(ReservationRepository repo, ShowRepository shows,
                              TransactionTemplate tx, MeterRegistry metrics) {
        this.repo = repo;
        this.shows = shows;
        this.tx = tx;
        this.metrics = metrics;
    }

    // Not @Transactional itself: I use TransactionTemplate so I control exactly what is inside
    // the transaction, and so metrics are bumped only AFTER the commit has really happened.
    public Outcome reserve(String userId, long showId, List<String> rawSeats, String idemKey) {
        // 1) cheap input checks, answered with 400 before touching any locks
        if (idemKey == null || idemKey.isBlank() || idemKey.length() > 128)
            throw new ApiException(400, "bad_request", "idempotency_key is required (max 128 chars)");
        if (rawSeats == null || rawSeats.isEmpty() || rawSeats.size() > MAX_SEATS_PER_REQUEST)
            throw new ApiException(400, "bad_request", "seats must be a list of 1 to " + MAX_SEATS_PER_REQUEST);

        TreeSet<String> sorted = new TreeSet<>();   // TreeSet = sorted AND de-duplicated
        for (String s : rawSeats) {
            if (s == null || s.isBlank() || s.trim().length() > 20)
                throw new ApiException(400, "bad_request", "invalid seat id");
            if (!sorted.add(s.trim()))
                throw new ApiException(400, "bad_request", "duplicate seat in request: " + s);
        }
        List<String> seats = new ArrayList<>(sorted);

        // 2) the show never changes after creation, so reading it outside the transaction is fine
        ShowRow show = shows.findShow(showId)
                .orElseThrow(() -> new ApiException(404, "not_found", "Show not found"));

        // The hash covers show + sorted seats, so ["A2","A1"] and ["A1","A2"] count as the same body,
        // while different seats under the same key are caught as a mismatch.
        String hash = sha256(showId + "|" + String.join(",", seats));
        long amount = show.pricePaise() * seats.size();   // paise * count, all integers

        // 3) make sure the quota row exists, OUTSIDE the transaction on purpose.
        // Inside it, INSERT IGNORE takes a shared lock on an existing row, and two parallel requests
        // from the same user would then both try to upgrade to an exclusive lock and deadlock.
        repo.ensureQuotaRow(showId, userId);

        for (int attempt = 1; ; attempt++) {
            try {
                Outcome out = tx.execute(status -> doReserve(userId, showId, seats, idemKey, hash, amount, show.perUserLimit()));
                // committed by now, so the counters match what is really in the database
                if (out.replay()) countDeclined("idempotent-replay");
                else metrics.counter("reservations.confirmed").increment();
                return out;
            } catch (PessimisticLockingFailureException e) {
                // MySQL picked us as a deadlock victim (1213) or we waited too long for a lock (1205).
                // The transaction is already rolled back, so retrying from scratch is safe.
                if (attempt >= MAX_ATTEMPTS)
                    throw new ApiException(429, "busy", "Too much contention, please retry");
                pause(attempt);
            } catch (ApiException e) {
                // expected business declines get counted by reason
                if (Set.of("seat-taken", "per-user-limit", "idempotency-mismatch").contains(e.getCode()))
                    countDeclined(e.getCode());
                throw e;
            }
        }
    }

    // Everything in here is ONE transaction. Order matters: idempotency, then quota, then seats.
    // Throwing an ApiException anywhere rolls the whole thing back.
    private Outcome doReserve(String userId, long showId, List<String> seats, String idemKey,
                              String hash, long amount, int limit) {
        String reservationId = UUID.randomUUID().toString();

        if (!repo.insertIdempotencyKey(userId, idemKey, hash, reservationId)) {
            // key already used by this user: either a legit retry or a different request reusing it
            ReservationRepository.IdemRow prev = repo.lockIdempotencyKey(userId, idemKey)
                    .orElseThrow(() -> new ApiException(409, "idempotency-conflict", "Key is being processed, retry"));
            if (!prev.requestHash().equals(hash))
                throw new ApiException(409, "idempotency-mismatch", "Idempotency key was used with a different request");
            ReservationResponse original = repo.findReservation(prev.reservationID())
                    .orElseThrow(() -> new ApiException(409, "idempotency-conflict", "Original reservation not found"));
            return new Outcome(original, true);   // nothing new is written
        }

        if (!repo.tryReserveQuota(showId, userId, seats.size(), limit))
            throw new ApiException(409, "per-user-limit", "Limit of " + limit + " seats per user reached for this show");

        // seats are already sorted, so every request locks them in the same order
        for (String seat : seats) {
            if (!repo.tryClaimSeat(showId, seat, userId, reservationId)) {
                if (repo.seatExists(showId, seat))
                    throw new ApiException(409, "seat-taken", "Seat " + seat + " is not available");
                throw new ApiException(404, "seat-not-found", "Seat " + seat + " does not exist in this show");
            }
        }

        repo.insertReservation(reservationId, showId, userId, String.join(",", seats), amount);
        return new Outcome(new ReservationResponse(reservationId, showId, userId, seats, amount, "confirmed"), false);
    }

    private void countDeclined(String reason) {
        metrics.counter("reservations.declined", "reason", reason).increment();
    }

    // small random wait so the retried transactions don't all collide again at the same moment
    private void pause(int attempt) {
        try {
            Thread.sleep(10L + ThreadLocalRandom.current().nextInt(40) * attempt);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);   // SHA-256 is guaranteed to exist on every JVM
        }
    }
}
