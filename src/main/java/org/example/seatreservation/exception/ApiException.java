package org.example.seatreservation.exception;

public class ApiException extends RuntimeException{
    public int getStatus() {
        return status;
    }

    public String getCode() {
        return code;
    }

    private final int status;

    public ApiException( int status, String code,String message) {
        super(message);
        this.code = code;
        this.status = status;
    }

    private final String code;

}
