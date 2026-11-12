package com.loyalty.order.messaging;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Thrown when Kafka did not confirm that it stored our event.
 *
 * {@code @ResponseStatus} makes Spring MVC answer 503 Service Unavailable: the request
 * was valid, but a system we depend on is down - the caller may safely try again later.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class EventPublishException extends RuntimeException {

    public EventPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
