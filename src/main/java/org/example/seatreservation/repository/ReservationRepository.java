package org.example.seatreservation.repository;

import org.example.seatreservation.model.ReservationResponse;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Arrays;
import java.util.Optional;

@Repository
public class ReservationRepository {
    public record  IdemRow(String requestHash, String reservationID){

    }

    public ReservationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private  final JdbcTemplate jdbcTemplate;

    //Callinf before the transcation starts (autocomit), see the service for why

    public  void ensureQuotaRow(long showId, String userID){
        jdbcTemplate.update("INSERT INTO user_show_quota (show_id, user_id, held) VALUES (?,?,0 )", showId, userID);
    }
// Idempotency: the primary key (user_id, idem_key) is the lock. If two identical requests
// arrive together, the second INSERT waits for the first to commit or roll back, and then
// either fails with a duplicate (first one won) or goes through (first one was declined)
    public boolean insertIdempotencyKey(String userId, String key, String hash, String resevertionId){
        try {
            jdbcTemplate.update("INSERT INTO idempotency_keys (user_id, idem_key, request_hash, reservation_id)"+ "VALUES (?,?,?,?)", userId, key, hash, resevertionId);
            return true;
        } catch (DuplicateKeyException e) {
            return false;
        }
    }
    // FOR SHARE = locking read, so it sees the latest committed row. A plain SELECT could read an
    // older snapshot (InnoDB's default REPEATABLE READ) and miss the row we just collided with.
    public Optional<IdemRow> lockIdempotencyKey(String userId, String key){
        return jdbcTemplate.query("SELECT request_hash, reservation_id FROM idempotency_keys" +"WHERE user_id =? AND idem_key =? FOR SHARE", (rs ,i)-> new IdemRow(rs.getString(1),rs.getString(2)), userId, key ).stream().findFirst();
    }
    // Per-user limit as ONE guarded UPDATE: only bumps the counter if the result stays within the limit.
    // 10 parallel requests from one user queue up on this row lock, so they can't all pass the check.
    public boolean tryReserveQuota(long showId, String userId, int n, int limit) {
        return jdbcTemplate.update("UPDATE user_show_quota SET held = held + ? "
                        + "WHERE show_id = ? AND user_id = ? AND held + ? <= ?",
                n, showId, userId, n, limit) == 1;
    }
    // THE atomic decision. Flips the seat only if it is still 'available'.
    // 500 people on A12: exactly one UPDATE changes a row, the other 499 change zero.
    public boolean tryClaimSeat(long showId, String seat, String userId, String reservationId) {
        return jdbcTemplate.update("UPDATE seats SET status = 'confirmed', user_id = ?, reservation_id = ? "
                        + "WHERE show_id = ? AND seat_no = ? AND status = 'available'",
                userId, reservationId, showId, seat) == 1;
    }

    // Only used after a failed claim, to tell "someone has it" (409) from "no such seat" (404).
    public boolean seatExists(long showId, String seat) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM seats WHERE show_id = ? AND seat_no = ?", Integer.class, showId, seat);
        return n != null && n > 0;
    }

    public void insertReservation(String id, long showId, String userId, String seatsCsv, long amountPaise) {
        jdbcTemplate.update("INSERT INTO reservations (id, show_id, user_id, seats, amount_paise, status) "
                + "VALUES (?, ?, ?, ?, ?, 'confirmed')", id, showId, userId, seatsCsv, amountPaise);
    }

    public Optional<ReservationResponse> findReservation(String id) {
        return jdbcTemplate.query("SELECT id, show_id, user_id, seats, amount_paise, status FROM reservations WHERE id = ?",
                (rs, i) -> new ReservationResponse(rs.getString(1), rs.getLong(2), rs.getString(3),
                        Arrays.asList(rs.getString(4).split(",")), rs.getLong(5), rs.getString(6)),
                id).stream().findFirst();
    }
}


