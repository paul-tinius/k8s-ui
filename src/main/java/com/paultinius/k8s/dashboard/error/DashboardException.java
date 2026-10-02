package com.paultinius.k8s.dashboard.error;

import org.springframework.http.HttpStatus;

public class DashboardException extends RuntimeException {

    private final HttpStatus status;

    public DashboardException(HttpStatus status, String message) {
        super(message);
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }

    public static DashboardException badRequest(String message) {
        return new DashboardException(HttpStatus.BAD_REQUEST, message);
    }

    public static DashboardException notFound(String message) {
        return new DashboardException(HttpStatus.NOT_FOUND, message);
    }
}
