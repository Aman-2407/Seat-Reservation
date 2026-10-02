package org.example.seatreservation.service;

import org.example.seatreservation.exception.ApiException;
import org.example.seatreservation.model.CreateShowRequest;
import org.example.seatreservation.model.SeatView;
import org.example.seatreservation.model.ShowRow;
import org.example.seatreservation.model.ShowRsponse;
import org.example.seatreservation.repository.ShowRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Service
public class ShowService {
    private  final ShowRepository repository;


    public ShowService(ShowRepository repository) {
        this.repository = repository;
    }
    // @Transactional so the show row and all its seats are saved together
    // If any seat insert fails halfway, everything rolls back and we don't end up
    // with a show that has only some of its seats
    @Transactional
    public ShowRsponse create(CreateShowRequest request){

        // Validate everything up front and answer with a clean 400 instead of
        // letting a bad input turn into a database error (which would be a 500).
        if (request == null || request.name() == null || request.name().isBlank())
            throw new ApiException(400,"bad_request","name is required");
        if (request.pricePaise() == null || request.pricePaise() <= 0)
            throw new ApiException(400,"bad_request","price_paise must be a positive number");
        if (request.seats() == null || request.seats().isEmpty())
            throw new ApiException(400,"bad_request", "seats must be a non-empty list");

        // Reject duplicate seat labels here. The (show_id, seat_no) primary key would
        // catch them too, but that would surface as a DB exception, not a nice 400.
        Set<String> unique = new HashSet<>();
        for (String s : request.seats()) {
            if (s == null || s.isBlank() || s.trim().length() > 20)
                throw new ApiException( 400,"bad_request", "invalid seat id");
            if (!unique.add(s.trim()))
                throw new ApiException( 400, "bad_request","duplicate seat: " + s);
        }

        long id = repository.insertShow(request.name().trim(), request.pricePaise());
        repository.insertSeats(id, List.copyOf(unique));
        return get(id);
    }

    // readOnly = true is a small hint to the driver and pool that this won't write anything.
    @Transactional(readOnly = true)
    public ShowRsponse get(long id) {
        ShowRow show = repository.findShow(id)
                .orElseThrow(() -> new ApiException(404, "not_found", "Show not found"));

        List<SeatView> seats = repository.findSeats(id);

        // Counting from the very same list I return, so the three counts and the total
        // can never disagree within one response. That's the reconciliation invariant.
        // (Worth revisiting once reservations run concurrently: this read should see
        // one consistent snapshot.)
        int available = 0, held = 0, confirmed = 0;
        for (SeatView s : seats) {
            switch (s.status()) {
                case "available" -> available++;
                case "held" -> held++;
                case "confirmed" -> confirmed++;
            }
        }
        return new ShowRsponse(show.id(), show.name(), show.pricePaise(), show.perUserLimit(),
                seats.size(), available, held, confirmed, seats);
    }
}
