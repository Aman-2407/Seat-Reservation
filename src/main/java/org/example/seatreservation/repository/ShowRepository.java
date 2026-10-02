package org.example.seatreservation.repository;

import org.example.seatreservation.model.SeatView;
import org.example.seatreservation.model.ShowRow;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;

// Plain JdbcTemplate instead of JPA on purpose. For the reserve logic I want to write the
// exact SQL myself, since the whole correctness story depends on specific guarded UPDATEs
// Doing the same here keeps the project consistent
@Repository
public class ShowRepository {

    public ShowRepository(JdbcTemplate template) {
        this.template = template;
    }

    private final JdbcTemplate template;

    //insert the show and hand back the auto-generated id so seats can point at it.
    public long insertShow(String name, long pricePaise){
        KeyHolder holder= new GeneratedKeyHolder();
        template.update(con -> {
            PreparedStatement ps= con.prepareStatement("INSERT INTO shows (name,price_paise) VALUES (?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, name);
            ps.setLong(2, pricePaise);
            return ps;
        },holder);
        return  holder.getKey().longValue();
    }
    // Seats go in as a batch (1000 per round trip) and a hall can have tens of thousands of
    // seats and one INSERT per seat would be painfully slow. Status defaults to
    // 'available' in the schema, so there's no need to set it here
    public void insertSeats(long showId, List<String> seats) {
        template.batchUpdate("INSERT INTO seats (show_id, seat_no) VALUES (?, ?)",
                seats, 1000, (ps, seat) -> {
                    ps.setLong(1, showId);
                    ps.setString(2, seat);
                });
    }
    // Optional instead of null so the service is forced to deal with "not found".
    public Optional<ShowRow> findShow(long id) {
        return template.query(
                "SELECT id, name, price_paise, per_user_limit FROM shows WHERE id = ?",
                (rs, i) -> new ShowRow(rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getInt(4)),
                id).stream().findFirst();
    }

    public List<SeatView> findSeats(long showId) {
        return template.query(
                "SELECT seat_no, status FROM seats WHERE show_id = ? ORDER BY seat_no",
                (rs, i) -> new SeatView(rs.getString(1), rs.getString(2)),
                showId);
    }
}
