package ir.ramtung.tinyme.domain.entity;

import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import org.jgroups.protocols.MAKE_BATCH;

public final class MatchResult {
    private final MatchingOutcome outcome;
    private final Order remainder;
    private final LinkedList<Trade> trades;

    public static MatchResult executed(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.EXECUTED, remainder, new LinkedList<>(trades));
    }

    public static MatchResult auctionExecuted(Order remainder, List<Trade> trades) {
        return new MatchResult(MatchingOutcome.AUCTION_EXECUTED, remainder, new LinkedList<>(trades));
    }

    public static MatchResult notEnoughCredit() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_CREDIT, null, new LinkedList<>());
    }
    public static MatchResult notEnoughPositions() {
        return new MatchResult(MatchingOutcome.NOT_ENOUGH_POSITIONS, null, new LinkedList<>());
    }
    public static MatchResult notSatisfiedMinExecQty(){
        return new MatchResult(MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED, null, new LinkedList<>());
    }

    public static MatchResult stopLimitOrderUpdatedSuccessfully(){
        return new MatchResult(MatchingOutcome.STOP_LIMIT_ORDER_UPDATED_SUCCESSFUL, null, new LinkedList<>());
    }

    public static MatchResult orderIsTradeable(){
        return new MatchResult(MatchingOutcome.TRADEABLE_ORDER, null, new LinkedList<>());
    }
    
    public static MatchResult inactiveStopLimit(){
        return new MatchResult(MatchingOutcome.INACTIVE_STOP_LIMIT_ORDER, null, new LinkedList<>());
    }

    private MatchResult(MatchingOutcome outcome, Order remainder, LinkedList<Trade> trades) {
        this.outcome = outcome;
        this.remainder = remainder;
        this.trades = trades;
    }

    public MatchingOutcome outcome() {
        return outcome;
    }
    public Order remainder() {
        return remainder;
    }

    public LinkedList<Trade> trades() {
        return trades;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) return true;
        if (obj == null || obj.getClass() != this.getClass()) return false;
        var that = (MatchResult) obj;
        return Objects.equals(this.remainder, that.remainder) &&
                Objects.equals(this.trades, that.trades);
    }

    @Override
    public int hashCode() {
        return Objects.hash(remainder, trades);
    }

    @Override
    public String toString() {
        return "MatchResult[" +
                "remainder=" + remainder + ", " +
                "trades=" + trades + ']';
    }

    public int getLastTradePrice()
    {
        if(!trades().isEmpty())
            return trades().getLast().getPrice();
        return 0;
    }


}
