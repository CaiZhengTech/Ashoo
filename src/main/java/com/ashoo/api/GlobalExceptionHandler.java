package com.ashoo.api;

import com.ashoo.api.dto.ErrorResponse;
import com.ashoo.reminder.ConsentRequiredException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.time.Instant;

/**
 * Translates exceptions into a single, consistent {@link ErrorResponse} JSON shape.
 *
 * Centralizing error handling here keeps controllers focused on the happy path and
 * guarantees clients see one error format regardless of where the failure originated.
 * Each handler maps a category of failure to the right HTTP status: client mistakes become
 * 4xx with a helpful message, while unexpected failures become a 500 whose internals are
 * logged server-side but never leaked to the caller.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps the consent gate to 403 Forbidden with the explanatory message.
     *
     * @param ex      the consent exception
     * @param request the failing request (for the path)
     * @return a 403 error body
     */
    @ExceptionHandler(ConsentRequiredException.class)
    public ResponseEntity<ErrorResponse> handleConsentRequired(ConsentRequiredException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.FORBIDDEN, ex.getMessage(), request);
    }

    /**
     * Maps bad arguments (e.g. a failed validation in a service) to 400 Bad Request.
     *
     * @param ex      the exception
     * @param request the failing request
     * @return a 400 error body
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex,
                                                               HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
    }

    /**
     * Maps a missing required query parameter (e.g. {@code from}/{@code to}) to 400.
     *
     * @param ex      the exception
     * @param request the failing request
     * @return a 400 error body naming the missing parameter
     */
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParam(MissingServletRequestParameterException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Missing required parameter: " + ex.getParameterName(), request);
    }

    /**
     * Maps an unparseable parameter (e.g. a malformed timestamp) to 400.
     *
     * @param ex      the exception
     * @param request the failing request
     * @return a 400 error body
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                            HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST,
                "Invalid value for parameter '" + ex.getName() + "'", request);
    }

    /**
     * Maps a malformed or unreadable request body to 400.
     *
     * @param ex      the exception
     * @param request the failing request
     * @return a 400 error body
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex,
                                                             HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, "Malformed request body", request);
    }

    /**
     * Catch-all for anything unanticipated, mapped to 500.
     *
     * The real exception is logged for diagnosis, but the client only receives a generic
     * message — internal details (stack traces, SQL) must never leak across the API boundary.
     *
     * @param ex      the unexpected exception
     * @param request the failing request
     * @return a 500 error body with a generic message
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception for {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred. Please try again later.", request);
    }

    private ResponseEntity<ErrorResponse> build(HttpStatus status, String message,
                                                HttpServletRequest request) {
        ErrorResponse body = new ErrorResponse(
                Instant.now(), status.value(), status.getReasonPhrase(),
                message, request.getRequestURI());
        return ResponseEntity.status(status).body(body);
    }
}
