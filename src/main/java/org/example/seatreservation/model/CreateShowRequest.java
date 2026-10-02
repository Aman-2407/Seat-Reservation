package org.example.seatreservation.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

// What the admin sends to POST /shows.
// price is a Long (boxed) rather than long so that a missing field shows up as null
// and I can return a proper 400, instead of silently treating it as 0
// Money is always paise as a whole number, never a float
public record CreateShowRequest(
    String name,
    List<String> seats,@JsonProperty("price_paise") Long pricePaise){

    }

