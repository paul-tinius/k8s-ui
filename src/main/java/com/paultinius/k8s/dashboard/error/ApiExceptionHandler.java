package com.paultinius.k8s.dashboard.error;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.util.DisconnectedClientHelper;

import java.util.Locale;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    @ExceptionHandler(DashboardException.class)
    public ResponseEntity<Map<String, String>> dashboard(
            DashboardException exception, HttpServletRequest request, HttpServletResponse response) {
        int status = exception.status().value();
        if (eventStream(request, response)) {
            return ResponseEntity.status(status).build();
        }
        return ResponseEntity.status(status).body(Map.of("error", exception.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(
            MethodArgumentNotValidException exception, HttpServletRequest request, HttpServletResponse response) {
        if (eventStream(request, response)) {
            return ResponseEntity.badRequest().build();
        }
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + " " + error.getDefaultMessage())
                .orElse("Request is invalid");
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, String>> unexpected(
            Exception exception, HttpServletRequest request, HttpServletResponse response) {
        // A text/event-stream response has no JSON converter. Writing the error map
        // fails the handler again, which is the warning this method used to raise.
        boolean disconnected = DisconnectedClientHelper.isClientDisconnectedException(exception);
        if (!disconnected) {
            log.error("Request failed", exception);
        }
        if (disconnected || eventStream(request, response)) {
            return ResponseEntity.internalServerError().build();
        }
        return ResponseEntity.internalServerError().body(Map.of("error", "The request failed"));
    }

    private static boolean eventStream(HttpServletRequest request, HttpServletResponse response) {
        String contentType = response.getContentType();
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.TEXT_EVENT_STREAM_VALUE)) {
            return true;
        }
        String accept = request.getHeader(HttpHeaders.ACCEPT);
        return accept != null && accept.toLowerCase(Locale.ROOT).contains(MediaType.TEXT_EVENT_STREAM_VALUE);
    }
}
