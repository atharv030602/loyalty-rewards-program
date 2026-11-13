package com.loyalty.order.api;

import com.loyalty.order.event.OrderPlacedEvent;
import com.loyalty.order.messaging.OrderEventPublisher;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Place orders that earn loyalty points")
public class OrderController {

    private final OrderEventPublisher publisher;

    // Constructor injection: the dependency is final and the class is easy to unit-test.
    public OrderController(OrderEventPublisher publisher) {
        this.publisher = publisher;
    }

    /**
     * Accepts an order and publishes an OrderPlaced event.
     *
     * Returns 202 Accepted (not 201 Created): we have reliably recorded the request,
     * but the real work - awarding points - happens later, in another service.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    @Operation(summary = "Place an order; publishes an OrderPlaced event to Kafka")
    public OrderAcceptedResponse placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        OrderPlacedEvent event = new OrderPlacedEvent(
                eventIdFor(request.orderId()),
                request.orderId(),
                request.memberId(),
                request.amount(),
                request.category().name(),
                Instant.now());

        publisher.publish(event);

        return new OrderAcceptedResponse(event.orderId(), event.eventId(), "ACCEPTED");
    }

    /**
     * The eventId is derived from the orderId (a "name-based" UUID), not random.
     * Same orderId -> same eventId, every time.
     *
     * Why: if a client times out and sends the same order again, a random id would look
     * like a brand-new event and the member would get points twice. With a derived id,
     * the idempotent consumer in rewards-service recognises the repeat and ignores it.
     */
    private static UUID eventIdFor(String orderId) {
        return UUID.nameUUIDFromBytes(("order-placed:" + orderId).getBytes(StandardCharsets.UTF_8));
    }
}
