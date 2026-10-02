package org.example.seatreservation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

// One seat as shown to the outside world: its label and whether it is
// available, held or confirmed
public record SeatView(@JsonProperty("seat_no") String seatNo, String status) {
}
