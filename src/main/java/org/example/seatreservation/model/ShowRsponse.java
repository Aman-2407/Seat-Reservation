package org.example.seatreservation.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;

import java.util.List;

// Full picture of a show for GET /shows/{id} and for the POST /shows reply
// The invariant to remember: available + held + confirmed == totalSeats, always
public record ShowRsponse(long id, String name, @JsonProperty("price_paise") long pricePaise, @JsonProperty("per_user_limit") int perUserLimit, @JsonProperty("total_seats") int totalSeats, int available, int held, int confirmed, List<SeatView> seats) {

}
