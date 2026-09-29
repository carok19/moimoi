package com.moimoi.local;

/** Error con código HTTP, como HTTPException en la API de la computadora. */
public final class ApiException extends Exception {
    public final int status;

    public ApiException(int status, String message) {
        super(message);
        this.status = status;
    }
}
