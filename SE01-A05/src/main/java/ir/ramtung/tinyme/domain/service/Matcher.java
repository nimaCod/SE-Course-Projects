package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ListIterator;

@Service
public class Matcher {
    public MatchResult match(Order newOrder) {
        OrderBook orderBook = newOrder.getSecurity().getOrderBook();
        LinkedList<Trade> trades = new LinkedList<>();

        while (orderBook.hasOrderOfType(newOrder.getSide().opposite()) && newOrder.getQuantity() > 0) {
            Order matchingOrder = orderBook.matchWithFirst(newOrder);
            if (matchingOrder == null)
                break;

            Trade trade = new Trade(newOrder.getSecurity(), matchingOrder.getPrice(), Math.min(newOrder.getQuantity(), matchingOrder.getQuantity()), newOrder, matchingOrder);
            if (newOrder.getSide() == Side.BUY) {
                if (trade.buyerHasEnoughCredit())
                    trade.decreaseBuyersCredit();
                else {
                    rollbackTrades(newOrder, trades);
                    return MatchResult.notEnoughCredit();
                }
            }
            trade.increaseSellersCredit();
            trades.add(trade);

            if (newOrder.getQuantity() >= matchingOrder.getQuantity()) {
                newOrder.decreaseQuantity(matchingOrder.getQuantity());
                orderBook.removeFirst(matchingOrder.getSide());
                if (matchingOrder instanceof IcebergOrder icebergOrder) {
                    icebergOrder.decreaseQuantity(matchingOrder.getQuantity());
                    icebergOrder.replenish();
                    if (icebergOrder.getQuantity() > 0)
                        orderBook.enqueue(icebergOrder);
                }
            } else {
                matchingOrder.decreaseQuantity(newOrder.getQuantity());
                newOrder.makeQuantityZero();
            }
        }

        MatchResult minExecQuanity = checkMinimumExecutionQuantity(trades, newOrder);
        if (minExecQuanity == MatchResult.notSatisfiedMinExecQty()){
            while(newOrder.getSecurity().getOrderBook().findByOrderId(newOrder.getSide(), newOrder.getOrderId()) != null){
                newOrder.getSecurity().getOrderBook().removeByOrderId(newOrder.getSide(), newOrder.getOrderId());
            }
        }
        return minExecQuanity;
    }

    private MatchResult checkMinimumExecutionQuantity(LinkedList<Trade> trades,Order newOrder){
        int totalTradesQuantity = 0;
        for (Trade trade : trades) {
            totalTradesQuantity += trade.getQuantity();
        } 
        if(totalTradesQuantity >= newOrder.getMinimumExecutionQuantity())
            return MatchResult.executed(newOrder, trades);
         else {
             rollbackTrades(newOrder, trades);
             return MatchResult.notSatisfiedMinExecQty();
        }
    }

    private void rollbackTrades(Order newOrder, LinkedList<Trade> trades) {
        if(newOrder.getSide() == Side.SELL)
            newOrder.getSecurity().getOrderBook().removeByOrderId(Side.SELL ,newOrder.getOrderId());
        else {
            newOrder.getBroker().increaseCreditBy(trades.stream().mapToLong(Trade::getTradedValue).sum());
            trades.forEach(trade -> trade.getSell().getBroker().decreaseCreditBy(trade.getTradedValue()));

            ListIterator<Trade> it = trades.listIterator(trades.size());
            while (it.hasPrevious()) {
                newOrder.getSecurity().getOrderBook().restoreSellOrder(it.previous().getSell());
            }
        }
    }

    public MatchResult execute(Order order) {
        MatchResult result = match(order);
        if (result.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT || result.outcome() == MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)
            return result;

        if (result.remainder().getQuantity() > 0) {
            if (order.getSide() == Side.BUY) {
                if (!order.getBroker().hasEnoughCredit((long)order.getPrice() * order.getQuantity())) {
                    rollbackTrades(order, result.trades());
                    return MatchResult.notEnoughCredit();
                }
                order.getBroker().decreaseCreditBy((long)order.getPrice() * order.getQuantity());
            }
            order.getSecurity().getOrderBook().enqueue(result.remainder());
        }
        if (!result.trades().isEmpty()) {
            for (Trade trade : result.trades()) {
                trade.getBuy().getShareholder().incPosition(trade.getSecurity(), trade.getQuantity());
                trade.getSell().getShareholder().decPosition(trade.getSecurity(), trade.getQuantity());
            }
        }
        return result;
    }

}
