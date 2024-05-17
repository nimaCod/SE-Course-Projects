package ir.ramtung.tinyme.domain.entity;
import java.util.LinkedList;
import java.util.List;

import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.domain.entity.StopLimitOrder;
import lombok.Builder;
import lombok.Getter;
import org.apache.activemq.artemis.utils.collections.UpdatableIterator;

@Getter
@Builder
public class Security {
    private String isin;
    @Builder.Default
    private int tickSize = 1;
    @Builder.Default
    private int lotSize = 1;
    @Builder.Default
    private OrderBook orderBook = new OrderBook();
    @Builder.Default
    private LinkedList<StopLimitOrder> stopLimitOrderList = new LinkedList<>();

    public MatchResult  newOrder(EnterOrderRq enterOrderRq, Broker broker, Shareholder shareholder, Matcher matcher) {
        if (enterOrderRq.getSide() == Side.SELL &&
                !shareholder.hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(shareholder) + enterOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();
        Order order;
        if (enterOrderRq.getPeakSize() == 0) {
            if(enterOrderRq.getStopPrice() == 0)
                order = new Order(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                        enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder, enterOrderRq.getEntryTime() , OrderStatus.NEW ,enterOrderRq.getMinimumExecutionQuantity());
            else { 
                    stopLimitOrderList.add( new StopLimitOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(), enterOrderRq.getQuantity(), enterOrderRq.getPrice(),enterOrderRq.getStopPrice(), broker, shareholder, enterOrderRq.getEntryTime() , OrderStatus.NEW));
                    return null;
                }
            }
        else 
            order = new IcebergOrder(enterOrderRq.getOrderId(), this, enterOrderRq.getSide(),
                    enterOrderRq.getQuantity(), enterOrderRq.getPrice(), broker, shareholder,
                    enterOrderRq.getEntryTime(), enterOrderRq.getPeakSize(), enterOrderRq.getMinimumExecutionQuantity());

        return matcher.execute(order);
    }

    public void deleteOrder(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
      if(!removeInactiveStopLimitOrder(deleteOrderRq.getOrderId())) {
          Order order = orderBook.findByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
          if (order == null)
              throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
          if (order.getSide() == Side.BUY)
              order.getBroker().increaseCreditBy(order.getValue());
          orderBook.removeByOrderId(deleteOrderRq.getSide(), deleteOrderRq.getOrderId());
      }
    }

    public StopLimitOrder findInactiveStopLimitOrder(long id)
    {
        for(var order : stopLimitOrderList)
            if(order.getOrderId() == id)
                return order;
        return null;
    }

    public boolean removeInactiveStopLimitOrder(long id)
    {
        var it = stopLimitOrderList.listIterator();
        while (it.hasNext()) {
            if (it.next().getOrderId() == id) {
                it.remove();
                return true;
            }
        }
        return false;
    }

    public void checkStopLimitOrderUpdateRequirements(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        if(updateOrderRq.getSide() == Side.BUY)
        {
            if((updateOrderRq.getStopPrice() >= updateOrderRq.getPrice()) || updateOrderRq.getStopPrice() <= 0)
                throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        }
        else{
            if((updateOrderRq.getStopPrice() <= updateOrderRq.getPrice()) || updateOrderRq.getStopPrice() <= 0)
                throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        }

        if(updateOrderRq.getPeakSize() > 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);
        if(updateOrderRq.getMinimumExecutionQuantity() > 0 )
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_MINIMUM_EXECUTION_QUANTITY_FOR_A_STOP_LIMIT_ORDER);
    }

    private boolean updateInactiveStopLimitOrder(EnterOrderRq updateOrderRq) throws InvalidRequestException {
        StopLimitOrder stopLimitOrder = findInactiveStopLimitOrder(updateOrderRq.getOrderId());
        if(stopLimitOrder == null)
            return false;
        checkStopLimitOrderUpdateRequirements(updateOrderRq);
        stopLimitOrder.updateFromRequest(updateOrderRq);
        return true;
    }

    public MatchResult updateOrder(EnterOrderRq updateOrderRq, Matcher matcher) throws InvalidRequestException {
        Order order = null;
        if(updateInactiveStopLimitOrder(updateOrderRq))
            return MatchResult.stopLimitOrderUpdatedSuccessfully();
        else
             order = orderBook.findByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        if (order == null)
            throw new InvalidRequestException(Message.ORDER_ID_NOT_FOUND);
        if ((order instanceof IcebergOrder) && updateOrderRq.getPeakSize() == 0)
            throw new InvalidRequestException(Message.INVALID_PEAK_SIZE);
        if(updateOrderRq.getStopPrice() != 0)
            throw new InvalidRequestException(Message.INVALID_STOP_PRICE);
        if (!(order instanceof IcebergOrder) && updateOrderRq.getPeakSize() != 0)
            throw new InvalidRequestException(Message.CANNOT_SPECIFY_PEAK_SIZE_FOR_A_NON_ICEBERG_ORDER);

        if (updateOrderRq.getSide() == Side.SELL &&
                !order.getShareholder().hasEnoughPositionsOn(this,
                orderBook.totalSellQuantityByShareholder(order.getShareholder()) - order.getQuantity() + updateOrderRq.getQuantity()))
            return MatchResult.notEnoughPositions();

        boolean losesPriority = order.isQuantityIncreased(updateOrderRq.getQuantity())
                || updateOrderRq.getPrice() != order.getPrice()
                || ((order instanceof IcebergOrder icebergOrder) && (icebergOrder.getPeakSize() < updateOrderRq.getPeakSize()));

        if (updateOrderRq.getSide() == Side.BUY) {
            order.getBroker().increaseCreditBy(order.getValue());
        }
        Order originalOrder = order.snapshot();
        order.updateFromRequest(updateOrderRq);
        if (!losesPriority) {
            if (updateOrderRq.getSide() == Side.BUY) {
                order.getBroker().decreaseCreditBy(order.getValue());
            }
            return MatchResult.executed(null, List.of());
        }

        orderBook.removeByOrderId(updateOrderRq.getSide(), updateOrderRq.getOrderId());
        MatchResult matchResult = matcher.execute(order);
        if (matchResult.outcome() != MatchingOutcome.EXECUTED) {
            orderBook.enqueue(originalOrder);
            if (updateOrderRq.getSide() == Side.BUY) {
                originalOrder.getBroker().decreaseCreditBy(originalOrder.getValue());
            }
        }
        return matchResult;
    }

    public LinkedList<Order> getLatestActivatedLimitOrders(int lastTradedPrice){
        LinkedList<Order> newTrades = new LinkedList<>() ; 
        if(stopLimitOrderList.isEmpty())
            return newTrades;
        for(StopLimitOrder limitOrder: stopLimitOrderList){
            if(limitOrder.getSide() == Side.BUY){
                if(limitOrder.getStopPrice() <= lastTradedPrice) {
                    newTrades.add(limitOrder.getSimpleOrder());
                    stopLimitOrderList.remove(limitOrder);
                }
            }

            else if(limitOrder.getSide() == Side.SELL){
                if(limitOrder.getStopPrice() >= lastTradedPrice) {
                    newTrades.add(limitOrder.getSimpleOrder());
                    stopLimitOrderList.remove(limitOrder);
                }
            }
        }
        return newTrades;
    }
}
