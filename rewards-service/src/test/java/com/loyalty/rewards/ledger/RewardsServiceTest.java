package com.loyalty.rewards.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.loyalty.rewards.event.OrderPlacedEvent;
import com.loyalty.rewards.points.PointsCalculator;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Unit tests for the earn and redeem rules. No Spring, no database:
 * the repository and the lock are Mockito mocks, so each test controls exactly
 * what "the database" answers. The points calculator is the real one.
 */
@ExtendWith(MockitoExtension.class)
class RewardsServiceTest {

    private static final String MEMBER = "M-42";

    @Mock
    private LedgerEntryRepository ledger;

    @Mock
    private MemberLock memberLock;

    private RewardsService service;

    @BeforeEach
    void setUp() {
        service = new RewardsService(ledger, new PointsCalculator(), memberLock);
    }

    // ---------- earning ----------

    @Test
    void newEventStoresAnEarnEntryWithTheCalculatedPoints() {
        OrderPlacedEvent event = event("ORD-2", "1000", "DINING");
        when(ledger.existsByEventId(event.eventId())).thenReturn(false);

        boolean awarded = service.awardPoints(event);

        assertThat(awarded).isTrue();
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger).saveAndFlush(captor.capture());
        LedgerEntry saved = captor.getValue();
        assertThat(saved.getEventId()).isEqualTo(event.eventId());
        assertThat(saved.getMemberId()).isEqualTo(MEMBER);
        assertThat(saved.getOrderId()).isEqualTo("ORD-2");
        assertThat(saved.getEntryType()).isEqualTo(EntryType.EARN);
        assertThat(saved.getPoints()).isEqualTo(30);
    }

    @Test
    void eventSeenBeforeIsIgnored() {
        OrderPlacedEvent event = event("ORD-1", "2500", "TRAVEL");
        when(ledger.existsByEventId(event.eventId())).thenReturn(true);

        boolean awarded = service.awardPoints(event);

        assertThat(awarded).isFalse();
        verify(ledger, never()).saveAndFlush(any());
    }

    @Test
    void duplicateCaughtByTheUniqueConstraintIsIgnored() {
        // Race: the check says "not seen", but another thread inserts before we do.
        OrderPlacedEvent event = event("ORD-1", "2500", "TRAVEL");
        when(ledger.existsByEventId(event.eventId())).thenReturn(false, true);
        when(ledger.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("duplicate key"));

        assertThat(service.awardPoints(event)).isFalse();
    }

    @Test
    void otherDatabaseErrorsAreNotSwallowed() {
        // The insert failed, but NOT because the event already exists -> must be rethrown
        // so that the Kafka error handler can retry.
        OrderPlacedEvent event = event("ORD-1", "2500", "TRAVEL");
        when(ledger.existsByEventId(event.eventId())).thenReturn(false, false);
        when(ledger.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("something else"));

        assertThatThrownBy(() -> service.awardPoints(event))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    // ---------- redeeming ----------

    @Test
    void redeemStoresANegativeEntryAndReturnsTheNewBalance() {
        when(ledger.existsByMemberId(MEMBER)).thenReturn(true);
        when(ledger.sumPointsByMemberId(MEMBER)).thenReturn(80L);

        long newBalance = service.redeem(MEMBER, 30);

        assertThat(newBalance).isEqualTo(50);
        ArgumentCaptor<LedgerEntry> captor = ArgumentCaptor.forClass(LedgerEntry.class);
        verify(ledger).save(captor.capture());
        assertThat(captor.getValue().getEntryType()).isEqualTo(EntryType.REDEEM);
        assertThat(captor.getValue().getPoints()).isEqualTo(-30);
        assertThat(captor.getValue().getMemberId()).isEqualTo(MEMBER);
        assertThat(captor.getValue().getEventId()).isNull();
    }

    @Test
    void redeemingTheWholeBalanceIsAllowed() {
        when(ledger.existsByMemberId(MEMBER)).thenReturn(true);
        when(ledger.sumPointsByMemberId(MEMBER)).thenReturn(80L);

        assertThat(service.redeem(MEMBER, 80)).isZero();
    }

    @Test
    void redeemingMoreThanTheBalanceIsRejectedAndNothingIsStored() {
        when(ledger.existsByMemberId(MEMBER)).thenReturn(true);
        when(ledger.sumPointsByMemberId(MEMBER)).thenReturn(80L);

        assertThatThrownBy(() -> service.redeem(MEMBER, 81))
                .isInstanceOf(InsufficientBalanceException.class)
                .hasMessageContaining("requested 81")
                .hasMessageContaining("available 80");

        verify(ledger, never()).save(any());
    }

    @Test
    void redeemForAnUnknownMemberIsRejected() {
        when(ledger.existsByMemberId("NOBODY")).thenReturn(false);

        assertThatThrownBy(() -> service.redeem("NOBODY", 10))
                .isInstanceOf(MemberNotFoundException.class);

        verify(ledger, never()).save(any());
    }

    @Test
    void redeemingZeroOrNegativePointsIsRejected() {
        assertThatThrownBy(() -> service.redeem(MEMBER, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.redeem(MEMBER, -10)).isInstanceOf(IllegalArgumentException.class);

        verify(ledger, never()).save(any());
    }

    @Test
    void theMemberLockIsTakenBeforeTheBalanceIsRead() {
        when(ledger.existsByMemberId(MEMBER)).thenReturn(true);
        when(ledger.sumPointsByMemberId(MEMBER)).thenReturn(80L);

        service.redeem(MEMBER, 30);

        // Reading the balance before locking would bring the double-spend bug back.
        InOrder order = inOrder(memberLock, ledger);
        order.verify(memberLock).lock(MEMBER);
        order.verify(ledger).sumPointsByMemberId(MEMBER);
        order.verify(ledger).save(any());
    }

    // ---------- reading ----------

    @Test
    void balanceOfAnUnknownMemberIsNotFound() {
        when(ledger.existsByMemberId("NOBODY")).thenReturn(false);

        assertThatThrownBy(() -> service.getBalance("NOBODY")).isInstanceOf(MemberNotFoundException.class);
        assertThatThrownBy(() -> service.getHistory("NOBODY")).isInstanceOf(MemberNotFoundException.class);
    }

    private static OrderPlacedEvent event(String orderId, String amount, String category) {
        return new OrderPlacedEvent(
                UUID.randomUUID(), orderId, MEMBER, new BigDecimal(amount), category, Instant.now());
    }
}
