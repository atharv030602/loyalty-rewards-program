package com.loyalty.rewards.api;

import com.loyalty.rewards.ledger.InsufficientBalanceException;
import com.loyalty.rewards.ledger.MemberNotFoundException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns exceptions into HTTP responses for every controller.
 *
 * All errors use the same JSON shape, "Problem Details" (RFC 9457), which Spring supports
 * out of the box: { "type", "title", "status", "detail", "instance" }.
 *
 * Extending ResponseEntityExceptionHandler gives the same shape to Spring's own errors
 * (malformed JSON, wrong HTTP method, unsupported media type...) for free.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 404: the resource in the URL does not exist. */
    @ExceptionHandler(MemberNotFoundException.class)
    public ProblemDetail handleMemberNotFound(MemberNotFoundException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
        problem.setTitle("Member not found");
        return problem;
    }

    /**
     * 409 Conflict: the request is well-formed, but it conflicts with the current state
     * (the balance). The client can fix it by asking for fewer points.
     */
    @ExceptionHandler(InsufficientBalanceException.class)
    public ProblemDetail handleInsufficientBalance(InsufficientBalanceException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Insufficient balance");
        problem.setProperty("requested", e.getRequested());
        problem.setProperty("available", e.getAvailable());
        return problem;
    }

    /** 400: a @Valid request body broke a rule. Add an "errors" map: field -> message. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

        ProblemDetail problem = ex.getBody();
        problem.setTitle("Validation failed");
        problem.setDetail("One or more fields are invalid");
        problem.setProperty("errors", errors);
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * 500: anything we did not expect. Log the full stack trace for us, but tell the
     * client nothing about our internals (class names and SQL are useful to attackers).
     */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
