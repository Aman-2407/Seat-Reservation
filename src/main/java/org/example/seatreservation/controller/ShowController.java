package org.example.seatreservation.controller;

import org.example.seatreservation.model.CreateShowRequest;
import org.example.seatreservation.model.ShowRsponse;
import org.example.seatreservation.service.ShowService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

// Kept thin on purpose: no logic in here, it only maps HTTP to service calls.
// Admin-only for POST is enforced earlier, in AuthFilter, so by the time a request
// reaches this method it's already been checked.
@RestController
public class ShowController {

    private final ShowService service;

    public ShowController(ShowService service) { this.service = service; }

    @PostMapping("/shows")
    @ResponseStatus(HttpStatus.CREATED)   // 201, as the assignment asks
    public ShowRsponse create(@RequestBody CreateShowRequest req) {
        return service.create(req);
    }

    @GetMapping("/shows/{id}")
    public ShowRsponse get(@PathVariable long id) {
        return service.get(id);
    }
}