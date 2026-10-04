package org.example.seatreservation.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.example.seatreservation.exception.ApiException;
import org.example.seatreservation.model.ReservationResponse;
import org.example.seatreservation.model.ReseveRequest;
import org.example.seatreservation.service.ReservationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReservationController {
    private final ReservationService service;

    public ReservationController(ReservationService service) { this.service = service; }

    @PostMapping("/shows/{id}/reserve")
    public ResponseEntity<ReservationResponse> reserve(
            @PathVariable("id") long showId,
            @RequestBody ReseveRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String headerKey,
            HttpServletRequest req) {

        // identity ONLY from the token (set by AuthFilter), never from the body
        String userId = (String) req.getAttribute("userId");

        // the key may come as a header or in the body; if both are present they must agree
        String bodyKey = body == null ? null : body.idempotencyKey();
        if (headerKey != null && bodyKey != null && !headerKey.equals(bodyKey))
            throw new ApiException(400, "bad_request", "Idempotency-Key header and body key differ");
        String key = headerKey != null ? headerKey : bodyKey;

        ReservationService.Outcome out = service.reserve(userId, showId, body == null ? null : body.seats(), key);

        // 201 for a fresh booking, 200 when this was a retry returning the original reservation
        return ResponseEntity.status(out.replay() ? HttpStatus.OK : HttpStatus.CREATED).body(out.reservationResponse());
    }
}
