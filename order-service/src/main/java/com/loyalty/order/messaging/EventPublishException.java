package com.loyalty.order.messaging;

/**
 * Thrown when Kafka did not confirm that it stored our event.
 * GlobalExceptionHandler turns it into HTTP 503 Service Unavailable.
 */
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
