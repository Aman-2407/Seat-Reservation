package org.example.seatreservation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
// Note there is deliberately NO user_id field here. Identity comes from the token only

public record ReseveRequest(List<String> seats, @JsonProperty("idempotency_key") String idempotencyKey) {
}
