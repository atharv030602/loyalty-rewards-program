package com.loyalty.rewards.api;

import com.loyalty.rewards.ledger.RewardsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST API for a member's points. The controller only translates HTTP <-> Java;
 * every rule lives in RewardsService, and every error is turned into a response
 * by GlobalExceptionHandler.
 */
@RestController
@RequestMapping("/members/{memberId}")
@Tag(name = "Members", description = "Points balance, history and redemption")
public class MemberController {

    private final RewardsService rewardsService;

    public MemberController(RewardsService rewardsService) {
        this.rewardsService = rewardsService;
    }

    @GetMapping("/balance")
    @Operation(summary = "Current points balance of a member")
    public BalanceResponse balance(@PathVariable String memberId) {
        return new BalanceResponse(memberId, rewardsService.getBalance(memberId));
    }

    @GetMapping("/history")
    @Operation(summary = "All points earned and redeemed by a member, newest first")
    public List<HistoryEntryResponse> history(@PathVariable String memberId) {
        return rewardsService.getHistory(memberId).stream()
                .map(HistoryEntryResponse::from)
                .toList();
    }

    @PostMapping("/redeem")
    @Operation(summary = "Spend points; rejected with 409 if the balance is too low")
    public RedeemResponse redeem(@PathVariable String memberId, @Valid @RequestBody RedeemRequest request) {
        long newBalance = rewardsService.redeem(memberId, request.points());
        return new RedeemResponse(memberId, request.points(), newBalance);
    }
}
