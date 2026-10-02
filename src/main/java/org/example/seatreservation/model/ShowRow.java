package org.example.seatreservation.model;

// Just the row from the shows table, nothing more.
// Kept separate from ShowResponse because the response also carries seats and counts
public record ShowRow(long id, String name, long pricePaise, int perUserLimit) {

}
