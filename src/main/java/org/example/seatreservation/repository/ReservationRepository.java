package org.example.seatreservation.repository;

import org.example.seatreservation.model.ReservationResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Optional;

// Every method here is one small SQL statement. The decisions (409 or not) live in the service.
// The important ones are the guarded UPDATEs: "UPDATE ... WHERE <still true?>" and then
// looking at how many rows changed. That check-and-change is a single atomic step in InnoDB,
// so there is no read-then-write gap for another request to sneak into.
//
// SQL is written as text blocks on purpose. With "..." + "..." concatenation I lost a space
// between a table name and WHERE once, and it only blew up on the idempotent-replay path.
@Repository
public class ReservationRepository {

    public record IdemRow(String requestHash, String reservationId) {}

    private final JdbcTemplate jdbc;

    public ReservationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    // Called BEFORE the transaction starts (autocommit), see the service for why.
    // ON DUPLICATE KEY UPDATE held = held is a deliberate no-op: if the row already exists we
    // leave it exactly as it is. A plain INSERT would throw on every second request from a user.
    public void ensureQuotaRow(long showId, String userId) {
        jdbc.update("""
                INSERT INTO user_show_quota (show_id, user_id, held)
                VALUES (?, ?, 0)
                ON DUPLICATE KEY UPDATE held = held
                """, showId, userId);
    }

    // Idempotency: the primary key (user_id, idem_key) is the lock. If two identical requests
    // arrive together, the second INSERT waits for the first to commit or roll back, and then
    // either fails with a duplicate (first one won) or goes through (first one was declined).
    public boolean insertIdempotencyKey(String userId, String key, String hash, String reservationId) {
        try {
            jdbc.update("""
                    INSERT INTO idempotency_keys (user_id, idem_key, request_hash, reservation_id)
                    VALUES (?, ?, ?, ?)
                    """, userId, key, hash, reservationId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }

    // FOR SHARE = locking read, so it sees the latest committed row. A plain SELECT could read an
    // older snapshot (InnoDB's default REPEATABLE READ) and miss the row we just collided with.
    public Optional<IdemRow> lockIdempotencyKey(String userId, String key) {
        return jdbc.query("""
                        SELECT request_hash, reservation_id
                        FROM idempotency_keys
                        WHERE user_id = ? AND idem_key = ?
                        FOR SHARE
                        """,
                (rs, i) -> new IdemRow(rs.getString(1), rs.getString(2)),
                userId, key).stream().findFirst();
    }

    // Per-user limit as ONE guarded UPDATE: only bumps the counter if the result stays within the limit.
    // 10 parallel requests from one user queue up on this row lock, so they can't all pass the check.
    public boolean tryReserveQuota(long showId, String userId, int n, int limit) {
        return jdbc.update("""
                UPDATE user_show_quota
                SET held = held + ?
                WHERE show_id = ? AND user_id = ? AND held + ? <= ?
                """, n, showId, userId, n, limit) == 1;
    }

    // THE atomic decision. Flips the seat only if it is still 'available'.
    // 500 people on A12: exactly one UPDATE changes a row, the other 499 change zero.
    public boolean tryClaimSeat(long showId, String seat, String userId, String reservationId) {
        return jdbc.update("""
                UPDATE seats
                SET status = 'confirmed', user_id = ?, reservation_id = ?
                WHERE show_id = ? AND seat_no = ? AND status = 'available'
                """, userId, reservationId, showId, seat) == 1;
    }

    // Only used after a failed claim, to tell "someone has it" (409) from "no such seat" (404).
    public boolean seatExists(long showId, String seat) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND seat_no = ?", Integer.class, showId, seat);
        return n != null && n > 0;
    }

    public void insertReservation(String id, long showId, String userId, String seatsCsv, long amountPaise) {
        jdbc.update("""
                INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status)
                VALUES (?, ?, ?, ?, ?, 'confirmed')
                """, id, showId, userId, seatsCsv, amountPaise);
    }

    public Optional<ReservationResponse> findReservation(String id) {
        return jdbc.query("""
                        SELECT id, show_id, user_id, seats, amount_paise, status
                        FROM reservations
                        WHERE id = ?
                        """,
                (rs, i) -> new ReservationResponse(rs.getString(1), rs.getLong(2), rs.getString(3),
                        Arrays.asList(rs.getString(4).split(",")), rs.getLong(5), rs.getString(6)),
                id).stream().findFirst();
    }

    // FOR UPDATE: two cancels of the same reservation (a double click, a retry) line up here,
    // so the second one sees the status the first one left behind.
    public Optional<ReservationResponse> findReservationForUpdate(String id) {
        return jdbc.query("""
                        SELECT id, show_id, user_id, seats, amount_paise, status
                        FROM reservations
                        WHERE id = ?
                        FOR UPDATE
                        """,
                (rs, i) -> new ReservationResponse(rs.getString(1), rs.getLong(2), rs.getString(3),
                        Arrays.asList(rs.getString(4).split(",")), rs.getLong(5), rs.getString(6)),
                id).stream().findFirst();
    }

    // Mirror of tryReserveQuota. "held >= ?" stops the counter from ever going negative.
    public boolean releaseQuota(long showId, String userId, int n) {
        return jdbc.update("""
                UPDATE user_show_quota
                SET held = held - ?
                WHERE show_id = ? AND user_id = ? AND held >= ?
                """, n, showId, userId, n) == 1;
    }

    // The guard on reservation_id + status is what makes a cancel unable to resurrect or steal a seat:
    // it only touches a seat that is still confirmed FOR THIS reservation.
    public boolean releaseSeat(long showId, String seat, String reservationId) {
        return jdbc.update("""
                UPDATE seats
                SET status = 'available', user_id = NULL, reservation_id = NULL
                WHERE show_id = ? AND seat_no = ? AND reservation_id = ? AND status = 'confirmed'
                """, showId, seat, reservationId) == 1;
    }

    public void markCancelled(String id) {
        jdbc.update("UPDATE reservations SET status = 'cancelled' WHERE id = ? AND status = 'confirmed'", id);
    }
}