package ir.ramtung.tinyme.domain.service;

import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.validator.*;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.EntityRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OrderHandler {
    RequestToOrderMapper idMapper;
    EntityRepository entityRepository;
    EventPublisher eventPublisher;
    Matcher matcher;

    public OrderHandler(SecurityRepository securityRepository, BrokerRepository brokerRepository,
            ShareholderRepository shareholderRepository, EventPublisher eventPublisher, Matcher matcher) {
        this.eventPublisher = eventPublisher;
        this.entityRepository = new EntityRepository(securityRepository, brokerRepository, shareholderRepository);
        this.matcher = matcher;
        this.idMapper = new RequestToOrderMapper();
    }

    private void checkMatchResult(MatchResult matchResult, EnterOrderRq enterOrderRq, boolean isStopLimit) {
        if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_CREDIT)
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));

        else if (matchResult.outcome() == MatchingOutcome.NOT_ENOUGH_POSITIONS)
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    List.of(Message.SELLER_HAS_NOT_ENOUGH_POSITIONS)));

        else if (matchResult.outcome() == MatchingOutcome.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)
            eventPublisher.publish(new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));

        else if (matchResult.outcome() == MatchingOutcome.AUCTION_EXECUTED)
            publishTrades(matchResult);
        else {
            publishUnrejectedMatchResult(matchResult, enterOrderRq, isStopLimit);
        }
    }

    private void publishUnrejectedMatchResult(MatchResult matchResult, EnterOrderRq enterOrderRq, boolean isStopLimit) {
        if (OrderRequestValidator.checkNewOrderIsNotStopLimit(enterOrderRq, isStopLimit))
            eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        else if (!isStopLimit)
            eventPublisher.publish(new OrderUpdatedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
        if (!MatchResultValidator.checkMatchResultHaveAnyTrades(matchResult))
            eventPublisher.publish(new OrderExecutedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(),
                    matchResult.trades().stream().map(TradeDTO::new).collect(Collectors.toList())));
    }

    public void publishTrades(MatchResult matchResult) {
        for (Trade trade : matchResult.trades())
            eventPublisher.publish(new TradeEvent(LocalDateTime.now().withNano(0).withSecond(0),trade));
    }
    public void handleEnterOrder(EnterOrderRq enterOrderRq) {
        idMapper.addNewIdMap(enterOrderRq);
        try {
            OrderRequestValidator.validateEnterOrderRq(enterOrderRq , entityRepository);
            EntityObj entities = entityRepository.getEntitiesOf(enterOrderRq);
            MatchResult matchResult = matchNewRequest(enterOrderRq, entities);
            if(matchResult.outcome() == MatchingOutcome.INACTIVE_STOP_LIMIT_ORDER){
                eventPublisher.publish(new OrderAcceptedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId()));
                return;
            }
            checkMatchResult(matchResult, enterOrderRq, false);
            activateNewAwokeStopLimitOrders(entities.security, matchResult);
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(
                    new OrderRejectedEvent(enterOrderRq.getRequestId(), enterOrderRq.getOrderId(), ex.getReasons()));
        }
    }

    private MatchResult matchNewRequest(EnterOrderRq enterOrderRq, EntityObj entities) throws InvalidRequestException {
        MatchResult matchResult;
        if (OrderRequestValidator.CheckRequestIsNewOrder(enterOrderRq))
            matchResult = entities.security.newOrder(enterOrderRq, entities, matcher);
        else
            matchResult = entities.security.updateOrder(enterOrderRq, matcher);
        return matchResult;
    }

    public void activateNewAwokeStopLimitOrders(Security security, MatchResult matchResult) {
        if (!MatchResultValidator.checkMatchResultHaveAnyTrades(matchResult))
            security.setLastPrice(matchResult.getLastTradePrice());
        int lastPrice = security.getLastPrice();
        LinkedList<Order> newAwokeOrder = security.getStopLimitOrderList().getLatestAwokedOrders(lastPrice);
        if (newAwokeOrder.isEmpty())
            return;
        activateAwokeStopLimitOrders(security, newAwokeOrder);
    }

    private void activateAwokeStopLimitOrders(Security security, LinkedList<Order> newActivatedOrder) {
        for (int i = 0; i < newActivatedOrder.size(); i++) {
            activateSingleStopLimitOrder(security, newActivatedOrder.get(i));
            appendNewActivatedStopLimitsToList(security, newActivatedOrder);
        }
    }

    private void appendNewActivatedStopLimitsToList(Security security, LinkedList<Order> newActivatedOrder) {
        int lastPrice = security.getLastPrice();
        newActivatedOrder.addAll(security.getLatestAwokedStopLimitOrders(lastPrice));
    }

    private void activateSingleStopLimitOrder(Security security, Order order) {
        long reqId = idMapper.getRequestID(order.getOrderId());
        eventPublisher.publish(new OrderActivatedEvent(reqId, order.getOrderId()));
        EnterOrderRq newActivateRq = order.convertOrderToRequest(reqId);
        EntityObj entities = entityRepository.getEntitiesOf(newActivateRq);
        MatchResult matchResult = security.newOrder(newActivateRq, entities, matcher);
        if (!MatchResultValidator.checkMatchResultHaveAnyTrades(matchResult)) {
            security.setLastPrice(matchResult.getLastTradePrice());
        }
        checkMatchResult(matchResult, newActivateRq, true);
    }

    public void handleDeleteOrder(DeleteOrderRq deleteOrderRq) {
        idMapper.removeMap(deleteOrderRq.getOrderId());
        try {
            OrderRequestValidator.validateDeleteOrderRq(deleteOrderRq , entityRepository);
            Security security = entityRepository.findSecurityByIsin(deleteOrderRq.getSecurityIsin());
            security.deleteOrder(deleteOrderRq);
            eventPublisher.publish(new OrderDeletedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId()));
        } catch (InvalidRequestException ex) {
            eventPublisher.publish(
                    new OrderRejectedEvent(deleteOrderRq.getRequestId(), deleteOrderRq.getOrderId(), ex.getReasons()));
        }
    }
}
