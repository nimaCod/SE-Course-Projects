package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.OrderEntryType;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.util.LinkedList;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    ArrayList<EnterOrderRq> enteredRequests = new ArrayList<>();
    SecurityRepository securityRepository;
    BrokerRepository brokerRepository;
    ShareholderRepository shareholderRepository;
    EventPublisher eventPublisher;
    Matcher matcher;
    private int lastPrice;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository, ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.securityRepository = securityRepository;
        this.brokerRepository = brokerRepository;
        this.shareholderRepository = shareholderRepository;
        this.eventPublisher = eventPublisher;
        this.matcher = matcher;
    }

    private void checkMatchResult(MatchResult matchResult,EnterOrderRq enterOrderRq){
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
            return;
        }
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));
            return;
        }
        if(matchResult.outcome() == MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED){
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
            return;
        }
        if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        else
            eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        if (!matchResult.trades().isEmpty()) {
            eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
        }
    }


    private long findRequestId(long orderId)
    {
        for(int i = enteredRequests.size()-1; i >= 0; i--){
            if (enteredRequests.get(i).getOrderId() == orderId)
                return enteredRequests.get(i).getRequestId();
        }
        return 0;
    }

    private long removeEnteredOrderRqByOrderID(long orderId)
    {
        enteredRequests.removeIf(rq -> rq.getOrderId() == orderId);
        return 0;
    }

    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        enteredRequests.add(enterOrderRq);
        try {
            validateEnterOrderRq(enterOrderRq);
            Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
            Broker broker = brokerRepository.findBrokerById(enterOrderRq.getBrokerId());
            Shareholder shareholder = shareholderRepository.findShareholderById(enterOrderRq.getShareholderId());
            MatchResult matchResult;
            if (enterOrderRq.getRequestType() == OrderEntryType.NEW_ORDER)
                matchResult = security.newOrder(enterOrderRq, broker, shareholder, matcher);
            else
                matchResult = security.updateOrder(enterOrderRq, matcher);

            if(matchResult == null){
                eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
                return;
            }
            checkMatchResult(matchResult,enterOrderRq);

            // if(matchResult.outcome() == MatchingOutcome.STOP_LIMIT_ORDER_UPDATED_SUCCESSFUL)
            //     return;

            if(!matchResult.trades().isEmpty()){
                lastPrice = matchResult.trades().getLast().getPrice();
            }
            LinkedList<Order> newActivatedOrder = security.getLatestActivatedLimitOrders(lastPrice);
            if (newActivatedOrder.isEmpty())
                return;
            
            for(int i =0;i<newActivatedOrder.size();i++){
                Order order = newActivatedOrder.get(i);
                eventPublisher.publish(new OrderActivatedEvent(findRequestId(order.getOrderId()), order.getOrderId()));
                // removeEnteredOrderRqByOrderID(order.getOrderId());
                EnterOrderRq newActivateRq = order.convertOrderToRequest(findRequestId(order.getOrderId()));
                removeEnteredOrderRqByOrderID(order.getOrderId());
                matchResult = security.newOrder(newActivateRq, broker, shareholder, matcher);
                lastPrice = matchResult.trades().getLast().getPrice(); 
                checkMatchResult(matchResult,newActivateRq);
                security.getLatestActivatedLimitOrders(lastPrice).stream().forEach(o -> newActivatedOrder.add(o));
            }   
            
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        removeEnteredOrderRqByOrderID(deleteOrderRq.getOrderId());
        try {
            validateDeleteOrderRq(deleteOrderRq);
            Security security = securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private void validateEnterOrderRq(EnterOrderRq enterOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (enterOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (enterOrderRq.getQuantity() <= 0)
            errors.add(Message.ORDER_QUANTITY_NOT_POSITIVE);
        if(enterOrderRq.getSide()==Side.BUY && (long) enterOrderRq.getQuantity() * enterOrderRq.getPrice() > brokerRepository.findBrokerById(enterOrderRq.getBrokerId()).getCredit())
            errors.add(Message.BUYER_HAS_NOT_ENOUGH_CREDIT);
        if (enterOrderRq.getPrice() <= 0)
            errors.add(Message.ORDER_PRICE_NOT_POSITIVE);
        Security security = securityRepository.findSecurityByIsin(enterOrderRq.getSecurityIsin());
        if (security == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        else {
            if (enterOrderRq.getQuantity() % security.getLotSize() != 0)
                errors.add(Message.QUANTITY_NOT_MULTIPLE_OF_LOT_SIZE);
            if (enterOrderRq.getPrice() % security.getTickSize() != 0)
                errors.add(Message.PRICE_NOT_MULTIPLE_OF_TICK_SIZE);
        }
        if (brokerRepository.findBrokerById(enterOrderRq.getBrokerId()) == null)
            errors.add(Message.UNKNOWN_BROKER_ID);
        if (shareholderRepository.findShareholderById(enterOrderRq.getShareholderId()) == null)
            errors.add(Message.UNKNOWN_SHAREHOLDER_ID);
        if (enterOrderRq.getPeakSize() < 0 || enterOrderRq.getPeakSize() >= enterOrderRq.getQuantity())
            errors.add(Message.INVALID_PEAK_SIZE);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }

    private void validateDeleteOrderRq(DeleteOrderRq deleteOrderRq) throws InvalidRequestException {
        List<String> errors = new LinkedList<>();
        if (deleteOrderRq.getOrderId() <= 0)
            errors.add(Message.INVALID_ORDER_ID);
        if (securityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin()) == null)
            errors.add(Message.UNKNOWN_SECURITY_ISIN);
        if (!errors.isEmpty())
            throw new InvalidRequestException(errors);
    }
}
