package com.loyalty.order.api;

import com.loyalty.order.messaging.EventPublishException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * One place that turns exceptions into HTTP responses for every controller.
 * All errors use the "Problem Details" JSON shape (RFC 9457):
 * { "type", "title", "status", "detail", "instance" }.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     * 400: the body could not be turned into a PlaceOrderRequest at all - broken JSON,
     * a missing body, text where a number is expected, or an unknown category.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {

        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status,
                "The request body is missing, is not valid JSON, or contains a value of the wrong type. "
                        + "category must be one of " + Arrays.toString(OrderCategory.values()));
        problem.setTitle("Malformed request");
        return handleExceptionInternal(ex, problem, headers, status, request);
    }

    /**
     * 503 Service Unavailable: the request was fine, but Kafka did not confirm the event.
     * Nothing was accepted, so the caller may safely send the same order again later.
     */
    @ExceptionHandler(EventPublishException.class)
    public ProblemDetail handlePublishFailure(EventPublishException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.SERVICE_UNAVAILABLE,
                "The order could not be accepted right now. Please try again later.");
        problem.setTitle("Order event could not be published");
        return problem;
    }

    /** 500: anything unexpected. Log the details for us; tell the client nothing internal. */
    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception e) {
        log.error("Unexpected error", e);
        return ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred");
    }
}
