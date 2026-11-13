package com.loyalty.rewards.api;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.loyalty.rewards.ledger.InsufficientBalanceException;
import com.loyalty.rewards.ledger.LedgerEntry;
import com.loyalty.rewards.ledger.MemberNotFoundException;
import com.loyalty.rewards.ledger.RewardsService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Component test of the web layer only: URL mapping, JSON in and out, validation,
 * and the HTTP status for every error. RewardsService is a mock, so there is no
 * database and no Kafka here.
 */
@WebMvcTest(MemberController.class)
class MemberControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private RewardsService rewardsService;

    @Test
    void balanceIsReturned() throws Exception {
        when(rewardsService.getBalance("M-42")).thenReturn(80L);

        mockMvc.perform(get("/members/M-42/balance"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value("M-42"))
                .andExpect(jsonPath("$.balance").value(80));
    }

    @Test
    void unknownMemberIs404WithAClearMessage() throws Exception {
        when(rewardsService.getBalance("NOBODY")).thenThrow(new MemberNotFoundException("NOBODY"));

        mockMvc.perform(get("/members/NOBODY/balance"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Member not found"))
                .andExpect(jsonPath("$.detail").value("Member NOBODY was not found"));
    }

    @Test
    void historyListsEntriesWithoutInternalFields() throws Exception {
        when(rewardsService.getHistory("M-42")).thenReturn(List.of(
                LedgerEntry.redeem("M-42", 30, "Redeemed 30 points"),
                LedgerEntry.earn(UUID.randomUUID(), "M-42", "ORD-2", 30, "Order ORD-2 (DINING, Rs 1000)"),
                LedgerEntry.earn(UUID.randomUUID(), "M-42", "ORD-1", 50, "Order ORD-1 (TRAVEL, Rs 2500)")));

        mockMvc.perform(get("/members/M-42/history"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].type").value("REDEEM"))
                .andExpect(jsonPath("$[0].points").value(-30))
                .andExpect(jsonPath("$[1].type").value("EARN"))
                .andExpect(jsonPath("$[1].orderId").value("ORD-2"))
                .andExpect(jsonPath("$[1].points").value(30))
                .andExpect(jsonPath("$[2].orderId").value("ORD-1"))
                // The API must not leak database ids or event ids.
                .andExpect(jsonPath("$[0].id").doesNotExist())
                .andExpect(jsonPath("$[1].eventId").doesNotExist());
    }

    @Test
    void redeemReturnsTheNewBalance() throws Exception {
        when(rewardsService.redeem("M-42", 30)).thenReturn(50L);

        mockMvc.perform(post("/members/M-42/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":30}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.memberId").value("M-42"))
                .andExpect(jsonPath("$.redeemedPoints").value(30))
                .andExpect(jsonPath("$.balance").value(50));
    }

    @Test
    void redeemingMoreThanTheBalanceIs409WithBothNumbers() throws Exception {
        when(rewardsService.redeem("M-42", 500)).thenThrow(new InsufficientBalanceException(500, 80));

        mockMvc.perform(post("/members/M-42/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":500}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient balance"))
                .andExpect(jsonPath("$.detail").value("Insufficient balance: requested 500 points, available 80"))
                .andExpect(jsonPath("$.requested").value(500))
                .andExpect(jsonPath("$.available").value(80));
    }

    @Test
    void redeemForUnknownMemberIs404() throws Exception {
        when(rewardsService.redeem("NOBODY", 10)).thenThrow(new MemberNotFoundException("NOBODY"));

        mockMvc.perform(post("/members/NOBODY/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":10}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void negativePointsAre400AndTheServiceIsNeverCalled() throws Exception {
        mockMvc.perform(post("/members/M-42/redeem")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"points":-5}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation failed"))
                .andExpect(jsonPath("$.errors.points").value("points must be greater than 0"));

        verify(rewardsService, never()).redeem(anyString(), anyLong());
    }

    @Test
    void missingPointsAre400() throws Exception {
        mockMvc.perform(post("/members/M-42/redeem").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.points").value("points is required"));
    }

    @Test
    void brokenJsonIs400() throws Exception {
        mockMvc.perform(post("/members/M-42/redeem").contentType(MediaType.APPLICATION_JSON).content("{oops"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unexpectedErrorIs500WithoutInternalDetails() throws Exception {
        when(rewardsService.getBalance("M-42")).thenThrow(new IllegalStateException("secret SQL details"));

        mockMvc.perform(get("/members/M-42/balance"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.detail").value("An unexpected error occurred"));
    }
}
