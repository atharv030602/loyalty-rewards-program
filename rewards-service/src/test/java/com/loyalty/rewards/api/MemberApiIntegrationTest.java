package com.loyalty.rewards.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.loyalty.rewards.IntegrationTestBase;
import com.loyalty.rewards.ledger.EntryType;
import com.loyalty.rewards.ledger.InsufficientBalanceException;
import com.loyalty.rewards.ledger.LedgerEntry;
import com.loyalty.rewards.ledger.LedgerEntryRepository;
import com.loyalty.rewards.ledger.RewardsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Integration tests for the REST API and the ledger against a real PostgreSQL:
 * real HTTP calls, real SQL, real constraints and locks.
 */
class MemberApiIntegrationTest extends IntegrationTestBase {

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private LedgerEntryRepository ledger;

    @Autowired
    private RewardsService rewardsService;

    @Test
    void balanceHistoryAndRedeemWorkTogether() {
        String member = memberWithPoints(50, 30);   // two orders: 50 + 30 = 80

        assertThat(balanceOf(member)).isEqualTo(80);

        ResponseEntity<Map> redeemed = rest.postForEntity(
                "/members/{id}/redeem", Map.of("points", 30), Map.class, member);
        assertThat(redeemed.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(((Number) redeemed.getBody().get("balance")).longValue()).isEqualTo(50);

        assertThat(balanceOf(member)).isEqualTo(50);

        ResponseEntity<List> history = rest.getForEntity("/members/{id}/history", List.class, member);
        assertThat(history.getStatusCode()).isEqualTo(HttpStatus.OK);
        List<Map<String, Object>> entries = history.getBody();
        assertThat(entries).hasSize(3);
        // Newest first: the redemption we just made is on top.
        assertThat(entries.get(0).get("type")).isEqualTo("REDEEM");
        assertThat(((Number) entries.get(0).get("points")).longValue()).isEqualTo(-30);
    }

    @Test
    void redeemingMoreThanTheBalanceIsRejectedAndChangesNothing() {
        String member = memberWithPoints(80);

        ResponseEntity<Map> response = rest.postForEntity(
                "/members/{id}/redeem", Map.of("points", 81), Map.class, member);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("detail"))
                .isEqualTo("Insufficient balance: requested 81 points, available 80");
        assertThat(balanceOf(member)).isEqualTo(80);
        assertThat(ledger.findByMemberIdOrderByCreatedAtDescIdDesc(member))
                .noneMatch(entry -> entry.getEntryType() == EntryType.REDEEM);
    }

    @Test
    void unknownMemberIs404() {
        ResponseEntity<Map> response = rest.getForEntity(
                "/members/{id}/balance", Map.class, "M-" + UUID.randomUUID());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody().get("title")).isEqualTo("Member not found");
    }

    @Test
    void invalidRedeemRequestIs400() {
        String member = memberWithPoints(80);

        ResponseEntity<Map> response = rest.postForEntity(
                "/members/{id}/redeem", Map.of("points", 0), Map.class, member);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(balanceOf(member)).isEqualTo(80);
    }

    @Test
    void databaseRefusesASecondRowForTheSameEvent() {
        // The last line of defence for idempotency: the UNIQUE constraint itself.
        UUID eventId = UUID.randomUUID();
        String member = "M-" + UUID.randomUUID();
        ledger.saveAndFlush(LedgerEntry.earn(eventId, member, "ORD-1", 50, "first"));

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        ledger.saveAndFlush(LedgerEntry.earn(eventId, member, "ORD-1", 50, "second")))
                .isInstanceOf(DataIntegrityViolationException.class);

        assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(50);
    }

    @Test
    void simultaneousRedemptionsCannotOverspend() throws Exception {
        // 100 points, 8 threads each trying to redeem 80 at the same instant.
        // Without the member lock several would succeed and the balance would go negative.
        String member = memberWithPoints(100);
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGun = new CountDownLatch(1);

        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            Callable<Boolean> attempt = () -> {
                startGun.await();
                try {
                    rewardsService.redeem(member, 80);
                    return true;
                } catch (InsufficientBalanceException e) {
                    return false;
                }
            };
            results.add(pool.submit(attempt));
        }
        startGun.countDown();

        int succeeded = 0;
        for (Future<Boolean> result : results) {
            if (result.get(30, TimeUnit.SECONDS)) {
                succeeded++;
            }
        }
        pool.shutdown();

        assertThat(succeeded).isEqualTo(1);
        assertThat(ledger.sumPointsByMemberId(member)).isEqualTo(20);
    }

    // ---------- helpers ----------

    /** Creates a new member with one EARN row per given amount. */
    private String memberWithPoints(long... earned) {
        String member = "M-" + UUID.randomUUID();
        int orderNumber = 1;
        for (long points : earned) {
            ledger.saveAndFlush(LedgerEntry.earn(
                    UUID.randomUUID(), member, "ORD-" + orderNumber++, points, "test order"));
        }
        return member;
    }

    private long balanceOf(String member) {
        ResponseEntity<Map> response = rest.getForEntity("/members/{id}/balance", Map.class, member);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return ((Number) response.getBody().get("balance")).longValue();
    }
}
