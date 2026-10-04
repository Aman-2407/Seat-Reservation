package org.example.seatreservation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

public record ReservationResponse(@JsonProperty("reservation_id") String reservationId,
                                 @JsonProperty("show_id") long showId,
                                 @JsonProperty("user_id") String userId,
                                 List<String> seats,
                                 @JsonProperty("amount_paise") long amountPaise,
                                 String status)  {

}
