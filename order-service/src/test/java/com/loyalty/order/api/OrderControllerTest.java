package com.loyalty.order.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loyalty.order.event.OrderPlacedEvent;
import com.loyalty.order.messaging.EventPublishException;
import com.loyalty.order.messaging.OrderEventPublisher;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Component test of the web layer only.
 *
 * {@code @WebMvcTest} starts just Spring MVC (controller, validation, JSON, exception
 * handler) - no Kafka, no Tomcat. The publisher is replaced by a Mockito mock, so we test
 * "given this HTTP request, what does the controller do and answer?" in milliseconds.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private OrderEventPublisher publisher;

    @Test
    void validOrderIsAcceptedAndPublished() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ORD-2","memberId":"M-42","amount":1000,"category":"DINING"}
                                """))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.orderId").value("ORD-2"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.eventId").isNotEmpty());

        ArgumentCaptor<OrderPlacedEvent> captor = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(publisher).publish(captor.capture());
        OrderPlacedEvent event = captor.getValue();
        assertThat(event.orderId()).isEqualTo("ORD-2");
        assertThat(event.memberId()).isEqualTo("M-42");
        assertThat(event.amount()).isEqualByComparingTo(new BigDecimal("1000"));
        assertThat(event.category()).isEqualTo("DINING");
        assertThat(event.eventId()).isNotNull();
        assertThat(event.occurredAt()).isNotNull();
    }

    @Test
    void sameOrderIdAlwaysGetsTheSameEventId() throws Exception {
        String body = """
                {"orderId":"ORD-7","memberId":"M-42","amount":500,"category":"TRAVEL"}
                """;
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted());

        ArgumentCaptor<OrderPlacedEvent> captor = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(publisher, times(2)).publish(captor.capture());
        List<OrderPlacedEvent> events = captor.getAllValues();
        // This is what lets rewards-service recognise a client retry as a duplicate.
        assertThat(events.get(0).eventId()).isEqualTo(events.get(1).eventId());
    }

    @Test
    void differentOrderIdsGetDifferentEventIds() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"ORD-1","memberId":"M-42","amount":2500,"category":"TRAVEL"}
                """)).andExpect(status().isAccepted());
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("""
                {"orderId":"ORD-2","memberId":"M-42","amount":1000,"category":"DINING"}
                """)).andExpect(status().isAccepted());

        ArgumentCaptor<OrderPlacedEvent> captor = ArgumentCaptor.forClass(OrderPlacedEvent.class);
        verify(publisher, times(2)).publish(captor.capture());
        assertThat(captor.getAllValues().get(0).eventId())
                .isNotEqualTo(captor.getAllValues().get(1).eventId());
    }

    @Test
    void invalidFieldsAreRejectedWithAMessagePerField() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"","memberId":"M-42","amount":-5,"category":"DINING"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.orderId").value("orderId is required"))
                .andExpect(jsonPath("$.errors.amount").value("amount must be greater than 0"));

        verify(publisher, never()).publish(any());
    }

    @Test
    void missingFieldsAreRejected() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.orderId").exists())
                .andExpect(jsonPath("$.errors.memberId").exists())
                .andExpect(jsonPath("$.errors.amount").exists())
                .andExpect(jsonPath("$.errors.category").exists());

        verify(publisher, never()).publish(any());
    }

    @Test
    void amountWithMoreThanTwoDecimalsIsRejected() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ORD-9","memberId":"M-42","amount":10.999,"category":"OTHER"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.amount").exists());
    }

    @Test
    void unknownCategoryIsRejectedAndTheAllowedValuesAreListed() throws Exception {
        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ORD-9","memberId":"M-42","amount":100,"category":"TRAVLE"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Malformed request"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("DINING")));

        verify(publisher, never()).publish(any());
    }

    @Test
    void brokenJsonIsRejected() throws Exception {
        mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void kafkaFailureBecomes503() throws Exception {
        doThrow(new EventPublishException("Kafka is down", new RuntimeException()))
                .when(publisher).publish(any());

        mockMvc.perform(post("/orders")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"orderId":"ORD-3","memberId":"M-42","amount":100,"category":"OTHER"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.title").value("Order event could not be published"));
    }
}
