package ir.ramtung.tinyme.domain;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.Broker;
import ir.ramtung.tinyme.domain.entity.Order;
import ir.ramtung.tinyme.domain.entity.OrderStatus;
import ir.ramtung.tinyme.domain.entity.Security;
import ir.ramtung.tinyme.domain.entity.Shareholder;
import ir.ramtung.tinyme.domain.entity.Side;
import ir.ramtung.tinyme.domain.entity.StopLimitOrder;
import ir.ramtung.tinyme.domain.entity.Trade;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderActivatedEvent;
import ir.ramtung.tinyme.messaging.event.OrderDeletedEvent;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.event.OrderUpdatedEvent;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class StopLimitTest {
    @Autowired
    OrderHandler orderHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    private Security security;
    private Shareholder shareholder;
    private Broker broker1, broker2, broker3;

    private List<Order> testOrders;

    // public Pair<Order, EnterOrderRq> makePairOrderAndOrderRq(long requestId, long orderId, Side side, int quantity, int price, int stopPrice){
    // EnterOrderRq rq = EnterOrderRq.createNewOrderRq(requestId, "ABC", orderId, LocalDateTime.now(), side, quantity, price, 2, shareholder.getShareholderId(), 0, 0, stopPrice);
    // Order order = new Order(orderId, security, side, quantity, price, broker2, shareholder);
    // Pair<Order, EnterOrderRq> pair = new Pair<>(order, rq);
    // return  {order, rq};
    // }

    void createTestOrders(Side side)
    {
        testOrders = List.of(
                new Order(200, security,side, 300, 1600, broker2, shareholder),
                new Order(300, security,side, 300, 1700, broker2, shareholder),
                new Order(400, security,side, 300, 1800, broker2, shareholder)
                );
        enqueueTestOrders();
    }

    void enqueueTestOrders()
    {
        for(var test : testOrders)
            security.getOrderBook().enqueue(test);
    }

    @BeforeEach
    void setup() {
        securityRepository.clear();
        brokerRepository.clear();
        shareholderRepository.clear();

        security = Security.builder().isin("ABC").build();
        securityRepository.addSecurity(security);

        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        shareholderRepository.addShareholder(shareholder);

        broker1 = Broker.builder().brokerId(1).credit(100_000_000).build();
        broker2 = Broker.builder().brokerId(2).credit(100_000_000).build();
        broker3 = Broker.builder().brokerId(2).credit(100_000_000).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }

    @Test
    void new_buy_stoplimit_matches_successfully(){
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1600));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.BUY, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalBuy = new Order(500, security, Side.BUY, 250, 1800, broker2, shareholder);
        Order newStopLimit = new StopLimitOrder(600, security, Side.BUY, 300, 1700, 1600, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order sell1 = new Order(200, security,Side.SELL, 300, 1600, broker2, shareholder);
        Order sell2 = new Order(300, security,Side.SELL, 300, 1700, broker2, shareholder);
        Order sell3 = new Order(400, security,Side.SELL, 300, 1800, broker2, shareholder);

        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        Trade trade0 = new Trade(security, 1600, 250, sell1, newNormalBuy);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 500, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 600));
        Trade trade1 = new Trade(security, 1600, 50, sell1, newStopLimit);
        Trade trade2 = new Trade(security, 1700, 250, sell2, newStopLimit);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 600, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
    }

    @Test
    void new_sell_stoplimit_matches_successfully(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 0, 1900));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalSell = new Order(500, security, Side.SELL, 250, 1600, broker2, shareholder);
        Order newStopLimit = new StopLimitOrder(600, security, Side.SELL, 300, 1500, 1900, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order buy1 = new Order(200, security,Side.BUY, 300, 1600, broker2, shareholder);
        Order buy2 = new Order(300, security,Side.BUY, 300, 1700, broker2, shareholder);
        Order buy3 = new Order(400, security,Side.BUY, 300, 1800, broker2, shareholder);

        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        Trade trade0 = new Trade(security, 1800, 250, buy3, newNormalSell);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 500, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 600));
        Trade trade1 = new Trade(security, 1800, 50, buy3, newStopLimit);
        Trade trade2 = new Trade(security, 1700, 250, buy2, newStopLimit);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 600, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
    }

    @Test
    void new_buy_stoplimit_not_activated(){
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 2000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.BUY, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalBuy = new Order(500, security, Side.BUY, 250, 1800, broker2, shareholder);
        Order newStopLimit = new StopLimitOrder(600, security, Side.BUY, 300, 1700, 2000, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order sell1 = new Order(200, security,Side.SELL, 300, 1600, broker2, shareholder);
        Order sell2 = new Order(300, security,Side.SELL, 300, 1700, broker2, shareholder);
        Order sell3 = new Order(400, security,Side.SELL, 300, 1800, broker2, shareholder);
        // 

        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        Trade trade0 = new Trade(security, 1600, 250, sell1, newNormalBuy);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 500, List.of(new TradeDTO(trade0))));

        assertThat(security.findInactiveStopLimitOrder(600)).isNotEqualTo(null);
    }

    @Test
    void new_sell_stoplimit_not_activated(){
        createTestOrders(Side.BUY);

        Order newNormalSell = new Order(500, security, Side.SELL, 250, 1600, broker2, shareholder);
        Order newStopLimit = new StopLimitOrder(600, security, Side.SELL, 300, 1500, 1900, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order buy1 = new Order(200, security,Side.BUY, 300, 1600, broker2, shareholder);
        Order buy2 = new Order(300, security,Side.BUY, 300, 1700, broker2, shareholder);
        Order buy3 = new Order(400, security,Side.BUY, 300, 1800, broker2, shareholder);

        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 0, 1000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));

        // 

        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        Trade trade0 = new Trade(security, 1800, 250, buy3, newNormalSell);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 500, List.of(new TradeDTO(trade0))));

        assertThat(security.findInactiveStopLimitOrder(600)).isNotEqualTo(null);
    }

    @Test
    void two_buy_sell_stoplimit_orders_added_with_same_stop_prices(){
        //
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1600));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1600));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 700, LocalDateTime.now(), Side.BUY, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalBuy = new Order(700, security, Side.BUY, 250, 1800, broker2, shareholder);
        Order newStopLimitId500 = new StopLimitOrder(500, security, Side.BUY, 300, 1700, 1600, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newStopLimitId600 = new StopLimitOrder(600, security, Side.BUY, 300, 1700, 1600, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order sell1 = new Order(200, security,Side.SELL, 300, 1600, broker2, shareholder);
        Order sell2 = new Order(300, security,Side.SELL, 300, 1700, broker2, shareholder);
        Order sell3 = new Order(400, security,Side.SELL, 300, 1800, broker2, shareholder);

        //
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 500)));
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(2, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 700)));

        Trade trade0 = new Trade(security, 1600, 250, sell1, newNormalBuy);
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 700, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 500));
        Trade trade1 = new Trade(security, 1600, 50, sell1, newStopLimitId500);
        Trade trade2 = new Trade(security, 1700, 250, sell2, newStopLimitId500);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 600));
        Trade trade3 = new Trade(security, 1700, 50, sell2, newStopLimitId600);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 600, List.of(new TradeDTO(trade3))));

        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 600)).isNotEqualTo(null);
    }

    @Test
    void two_new_sell_stoplimit_orders_added_with_same_stop_prices(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1900));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1900));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 700, LocalDateTime.now(), Side.SELL, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newStopLimitId500 = new StopLimitOrder(500, security, Side.SELL, 300, 1700, 1900, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newStopLimitId600 = new StopLimitOrder(600, security, Side.SELL, 300, 1700, 1900, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newNormalSell = new Order(700, security, Side.SELL, 250, 1600, broker2, shareholder);
        Order buy1 = new Order(200, security,Side.BUY, 300, 1600, broker2, shareholder);
        Order buy2 = new Order(300, security,Side.BUY, 300, 1700, broker2, shareholder);
        Order buy3 = new Order(400, security,Side.BUY, 300, 1800, broker2, shareholder);

        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 500)));
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(2, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 700)));

        Trade trade0 = new Trade(security, 1800, 250, buy3, newNormalSell);
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 700, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 500));
        Trade trade1 = new Trade(security, 1800, 50, buy3, newStopLimitId500);
        Trade trade2 = new Trade(security, 1700, 250, buy2, newStopLimitId500);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 600));
        Trade trade3 = new Trade(security, 1700, 50, buy2, newStopLimitId600);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 600, List.of(new TradeDTO(trade3))));

        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isNotEqualTo(null);
    }

    @Test
    void two_buy_sell_stoplimit_orders_added_with_different_stop_prices(){
        //
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1550));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1600));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 700, LocalDateTime.now(), Side.BUY, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalBuy = new Order(700, security, Side.BUY, 250, 1800, broker2, shareholder);
        Order newStopLimitId500 = new StopLimitOrder(500, security, Side.BUY, 300, 1700, 1600, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newStopLimitId600 = new StopLimitOrder(600, security, Side.BUY, 300, 1700, 1600, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order sell1 = new Order(200, security,Side.SELL, 300, 1600, broker2, shareholder);
        Order sell2 = new Order(300, security,Side.SELL, 300, 1700, broker2, shareholder);
        Order sell3 = new Order(400, security,Side.SELL, 300, 1800, broker2, shareholder);

        //
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 500)));
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(2, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 700)));

        Trade trade0 = new Trade(security, 1600, 250, sell1, newNormalBuy);
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 700, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 500));
        Trade trade1 = new Trade(security, 1600, 50, sell1, newStopLimitId500);
        Trade trade2 = new Trade(security, 1700, 250, sell2, newStopLimitId500);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 600));
        Trade trade3 = new Trade(security, 1700, 50, sell2, newStopLimitId600);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 600, List.of(new TradeDTO(trade3))));

        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 600)).isNotEqualTo(null);
    }

    @Test
    void two_new_sell_stoplimit_orders_added_with_different_stop_prices(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 2000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1900));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 700, LocalDateTime.now(), Side.SELL, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newStopLimitId500 = new StopLimitOrder(500, security, Side.SELL, 300, 1700, 2000, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newStopLimitId600 = new StopLimitOrder(600, security, Side.SELL, 300, 1700, 1900, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newNormalSell = new Order(700, security, Side.SELL, 250, 1600, broker2, shareholder);
        Order buy1 = new Order(200, security,Side.BUY, 300, 1600, broker2, shareholder);
        Order buy2 = new Order(300, security,Side.BUY, 300, 1700, broker2, shareholder);
        Order buy3 = new Order(400, security,Side.BUY, 300, 1800, broker2, shareholder);

        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 500)));
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(2, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 700)));

        Trade trade0 = new Trade(security, 1800, 250, buy3, newNormalSell);
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 700, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 500));
        Trade trade1 = new Trade(security, 1800, 50, buy3, newStopLimitId500);
        Trade trade2 = new Trade(security, 1700, 250, buy2, newStopLimitId500);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 600));
        Trade trade3 = new Trade(security, 1700, 50, buy2, newStopLimitId600);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 600, List.of(new TradeDTO(trade3))));

        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isNotEqualTo(null);
    }

    @Test
    void update_inactive_sell_stoplimit_and_then_matches(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 0, 1000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalSell = new Order(500, security, Side.SELL, 250, 1600, broker2, shareholder);
        Order newStopLimit = new StopLimitOrder(600, security, Side.SELL, 300, 1500, 1000, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order buy1 = new Order(200, security,Side.BUY, 300, 1600, broker2, shareholder);
        Order buy2 = new Order(300, security,Side.BUY, 300, 1700, broker2, shareholder);
        Order buy3 = new Order(400, security,Side.BUY, 300, 1800, broker2, shareholder);

        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        Trade trade0 = new Trade(security, 1800, 250, buy3, newNormalSell);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 500, List.of(new TradeDTO(trade0))));

        //
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(3, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 1900));
        verify(eventPublisher).publish(new OrderUpdatedEvent(3, 600));
        verify(eventPublisher).publish(new OrderActivatedEvent(3, 600));
        Trade trade1 = new Trade(security, 1800, 50, buy3, newStopLimit);
        Trade trade2 = new Trade(security, 1700, 250, buy2, newStopLimit);
        verify(eventPublisher).publish(new OrderExecutedEvent(3, 600, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
    }

    @Test
    void update_active_sell_stoplimit_and_doesnt_matche(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 0, 1000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalSell = new Order(500, security, Side.SELL, 250, 1600, broker2, shareholder);
        Order newStopLimit = new StopLimitOrder(600, security, Side.SELL, 300, 1500, 1000, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order buy1 = new Order(200, security,Side.BUY, 300, 1600, broker2, shareholder);
        Order buy2 = new Order(300, security,Side.BUY, 300, 1700, broker2, shareholder);
        Order buy3 = new Order(400, security,Side.BUY, 300, 1800, broker2, shareholder);

        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        Trade trade0 = new Trade(security, 1800, 250, buy3, newNormalSell);
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 500, List.of(new TradeDTO(trade0))));

        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(3, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 1600));
        assertThat(security.findInactiveStopLimitOrder(600)).isNotEqualTo(null);
    }

    @Test
    void chain_of_sell_stoplimit_order_activation(){
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 1600));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.BUY, 300, 1800, 2, shareholder.getShareholderId(), 0, 0, 1700));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(3, "ABC", 700, LocalDateTime.now(), Side.BUY, 300, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));

        Order newNormalBuy = new Order(700, security, Side.BUY, 300, 1600, broker2, shareholder);
        Order newStopLimitId500 = new StopLimitOrder(500, security, Side.BUY, 300, 1700, 1600, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order newStopLimitId600 = new StopLimitOrder(600, security, Side.BUY, 300, 1800, 1700, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW) ;
        Order sell1 = new Order(200, security,Side.SELL, 300, 1600, broker2, shareholder);
        Order sell2 = new Order(300, security,Side.SELL, 300, 1700, broker2, shareholder);
        Order sell3 = new Order(400, security,Side.SELL, 300, 1800, broker2, shareholder);

        verify(eventPublisher, times(2)).publish((new OrderAcceptedEvent(1, 500)));
        verify(eventPublisher, times(2)).publish((new OrderAcceptedEvent(2, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(3, 700)));

        // verify(eventPublisher).publish(new OrderActivatedEvent(1, 600));
        Trade trade0 = new Trade(security, 1600, 300, sell1, newNormalBuy);
        Trade trade1 = new Trade(security, 1700, 300, sell2, newStopLimitId500);
        Trade trade2 = new Trade(security, 1800, 300, sell3, newStopLimitId600);

        verify(eventPublisher).publish(new OrderExecutedEvent(3, 700, List.of(new TradeDTO(trade0))));

        verify(eventPublisher).publish(new OrderActivatedEvent(1, 500));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1))));

        verify(eventPublisher).publish(new OrderActivatedEvent(2, 600));
        verify(eventPublisher).publish(new OrderExecutedEvent(2, 600, List.of(new TradeDTO(trade2))));
    }

    
    @Test
    void delete_sell_stoplimit_that_activated(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, security.getIsin(), 600, LocalDateTime.now(), Side.SELL, 5000, 1500, 2, shareholder.getShareholderId(), 0, 0, 1900));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, security.getIsin(), 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));

        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        long  brokerCredit = broker2.getCredit();
        orderHandler.handleDeleteOrder(new DeleteOrderRq(5, security.getIsin(), Side.SELL, 600));
        verify(eventPublisher).publish(new OrderDeletedEvent(5, 600));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
        assertThat(security.findInactiveStopLimitOrder(600)).isEqualTo(null);
        assertThat(broker2.getCredit()).isEqualTo(brokerCredit);
    }

    @Test
    void delete_sell_stoplimit_not_activated(){
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 300, 1500, 2, shareholder.getShareholderId(), 0, 0, 1000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.SELL, 250, 1600, 2, shareholder.getShareholderId(), 0, 0, 0));
        assertThat(security.findInactiveStopLimitOrder(600)).isNotEqualTo(null); // Not activated

        long  brokerCredit = broker2.getCredit();
        orderHandler.handleDeleteOrder(new DeleteOrderRq(5, security.getIsin(), Side.SELL, 600));
        verify(eventPublisher).publish(new OrderDeletedEvent(5, 600));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
        assertThat(security.findInactiveStopLimitOrder(600)).isEqualTo(null);
        assertThat(broker2.getCredit()).isEqualTo(brokerCredit);
    }

    @Test
    void delete_buy_stoplimit_that_activated(){
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 500, 1700, 2, shareholder.getShareholderId(), 0, 0, 1600));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.BUY, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));
        verify(eventPublisher,times(2)).publish((new OrderAcceptedEvent(1, 600)));
        verify(eventPublisher).publish((new OrderAcceptedEvent(2, 500)));
        verify(eventPublisher).publish(new OrderActivatedEvent(1, 600));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);

        long  brokerCredit = broker2.getCredit();
        orderHandler.handleDeleteOrder(new DeleteOrderRq(3, security.getIsin(), Side.BUY, 600));
        verify(eventPublisher).publish(new OrderDeletedEvent(3, 600));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 600)).isEqualTo(null);
        assertThat(security.findInactiveStopLimitOrder(600)).isEqualTo(null);
        assertThat(broker2.getCredit()).isEqualTo(brokerCredit);
    }

    @Test
    void delete_buy_stoplimit_not_activated(){
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 300, 1700, 2, shareholder.getShareholderId(), 0, 0, 2000));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 500, LocalDateTime.now(), Side.BUY, 250, 1800, 2, shareholder.getShareholderId(), 0, 0, 0));
        assertThat(security.findInactiveStopLimitOrder(600)).isNotEqualTo(null); // Not activated

        long  brokerCredit = broker2.getCredit();
        orderHandler.handleDeleteOrder(new DeleteOrderRq(5, security.getIsin(), Side.SELL, 600));
        verify(eventPublisher).publish(new OrderDeletedEvent(5, 600));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 600)).isEqualTo(null);
        assertThat(security.findInactiveStopLimitOrder(600)).isEqualTo(null);
        assertThat(broker2.getCredit()).isEqualTo(brokerCredit);
    }

    @Test
    void update_inactive_sell_stopLimit_order_rejected_due_to_wrong_stop_price()
    {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.SELL, 900, 1500, 2, shareholder.getShareholderId(), 0, 0, 1800));
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.SELL, 3000, 2000, 2, shareholder.getShareholderId(), 0, 1900));
        verify(eventPublisher).publish(new OrderRejectedEvent(2 , 600 , List.of(Message.INVALID_STOP_PRICE)));
    }

    @Test
    void update_inactive_buy_stopLimit_order_rejected_due_to_wrong_stop_price()
    {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 900, 1500, 2, shareholder.getShareholderId(), 0, 0, 1800));
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.BUY, 3000, 2000, 2, shareholder.getShareholderId(), 0, 2500));
        verify(eventPublisher).publish(new OrderRejectedEvent(2 , 600 , List.of(Message.INVALID_STOP_PRICE)));
    }

    @Test
    void update_inactive_stopLimit_order_rejected_due_to_insufficient_balance()
    {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 900, 1500, 2, shareholder.getShareholderId(), 0, 0, 1800));
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(2, "ABC", 600, LocalDateTime.now(), Side.BUY, 300000, 1500000, 2, shareholder.getShareholderId(), 0, 1800000));
        verify(eventPublisher).publish(new OrderRejectedEvent(2 , 600 , List.of(Message.BUYER_HAS_NOT_ENOUGH_CREDIT)));
    }

    @Test
    void successful_delete_inactive_stop_limit_order()
    {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 600, LocalDateTime.now(), Side.BUY, 900, 1500, 2, shareholder.getShareholderId(), 0, 0, 1800));
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 600)));
        orderHandler.handleDeleteOrder(new DeleteOrderRq(2, "ABC", Side.BUY, 600));
        verify(eventPublisher).publish(new OrderDeletedEvent(2 , 600 ));
    }

}