package ir.ramtung.tinyme.domain;
import ir.ramtung.tinyme.messaging.TradeDTO;
import ir.ramtung.tinyme.messaging.event.OrderExecutedEvent;
import org.junit.jupiter.api.Test;
import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.OrderAcceptedEvent;
import ir.ramtung.tinyme.messaging.event.OrderRejectedEvent;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class MinimumExecutionQuantityTest {
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
    void new_sell_minQu_order_matches_successfully() {
        Order incomingBuyOrder = new Order(200, security, Side.BUY, 300, 15700, broker2, shareholder);
        security.getOrderBook().enqueue(incomingBuyOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.SELL, 300, 15500, 2, shareholder.getShareholderId(), 0, 100));
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 300)));
        Order sellOrder = new Order(300, security, Side.SELL, 300, 15700, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW, 100);
        Trade trade = new Trade(security, 15700, 300, incomingBuyOrder, sellOrder);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 300, List.of(new TradeDTO(trade))));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 300)).isEqualTo(null);
    }

    @Test
    void new_buy_minQu_order_matches_successfully() {
        Order incomingSellOrder = new Order(200, security, Side.SELL, 300, 15450, broker2, shareholder);
        security.getOrderBook().enqueue(incomingSellOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.BUY, 300, 15450, 2, shareholder.getShareholderId(), 0, 100));
        verify(eventPublisher).publish((new OrderAcceptedEvent(1, 300)));
        Order buyOrder = new Order(300, security, Side.BUY, 300, 15450, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW, 100);
        Trade trade = new Trade(security, 15450, 300, incomingSellOrder, buyOrder);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 300, List.of(new TradeDTO(trade))));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 300)).isEqualTo(null);
    }

    @Test
    void new_sell_minQu_order_rejected_with_no_matching() {
        Order incomingBuyOrder = new Order(200, security, Side.BUY, 300, 15000, broker2, shareholder);
        security.getOrderBook().enqueue(incomingBuyOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.SELL, 300, 16000, 2, shareholder.getShareholderId(), 0, 100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 300, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 300)).isEqualTo(null);
    }

    @Test
    void new_buy_minQu_order_rejected_with_no_matching() {
        Order incomingSellOrder = new Order(200, security, Side.SELL, 300, 16000, broker2, shareholder);
        security.getOrderBook().enqueue(incomingSellOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.BUY, 300, 15000, 2, shareholder.getShareholderId(), 0, 100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 300, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 300)).isEqualTo(null);
    }

    @Test
    void new_sell_minQu_order_rejected_in_middle_of_buy_queue() {
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 1000, 1700, 2, shareholder.getShareholderId(), 0, 700));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 500, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 500)).isEqualTo(null);
    }

    @Test
    void new_buy_minQu_order_rejected_in_middle_of_sell_queue() {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 1000, 1700, 2, shareholder.getShareholderId(), 0, 700));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 500, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 500)).isEqualTo(null);
    }

    @Test
    void new_buy_minQu_order_rejected_when_sell_queue_is_finnished() {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 20000, 1800, 2, shareholder.getShareholderId(), 0, 1000));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 500, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 500)).isEqualTo(null);
    }

    @Test
    void new_sell_minQu_order_rejected_when_buy_queue_is_finnished() {
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 1000, 1700, 1, shareholder.getShareholderId(), 0 , 700));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 500, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 500)).isEqualTo(null);
    }

    @Test
    void new_buy_minQu_order_remains_in_queue_when_minimumExecution_is_Satisfied() {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 20000, 1700, 2, shareholder.getShareholderId(), 0, 500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1 , 500));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 500)).isNotEqualTo(null);
        //check trades
        Order minQuOrder = new Order(500, security, Side.SELL, 1000, 1700, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW, 500);
        Trade trade1 = new Trade(security, 1600, 300, testOrders.get(0), minQuOrder);
        Trade trade2 = new Trade(security, 1700, 300, testOrders.get(1), minQuOrder);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void new_sell_minQu_order_remains_in_queue_when_minimumExecution_is_Satisfied() {
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 1000, 1700, 2, shareholder.getShareholderId(), 0, 500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1 , 500));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 500)).isNotEqualTo(null);
        //check trades
        Order minQuOrder = new Order(500, security, Side.SELL, 1000, 1700, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW, 500);
        Trade trade1 = new Trade(security, 1800, 300, minQuOrder, testOrders.get(2));
        Trade trade2 = new Trade(security, 1700, 300, minQuOrder, testOrders.get(1));
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }

    @Test
    void minimumExecutionQuantity_not_being_checked_after_update_buy_order() {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 1000, 1700, 2, shareholder.getShareholderId(), 0, 500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 500));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 1000, 1750, 2, shareholder.getShareholderId(), 0));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 500));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 300)).isEqualTo(null);
        //check trades
        Order minQuOrder = new Order(500, security, Side.BUY, 1000, 1700, broker2, shareholder, LocalDateTime.now(), OrderStatus.NEW, 500);
        Trade trade1 = new Trade(security, 1600, 300, testOrders.get(0), minQuOrder);
        Trade trade2 = new Trade(security, 1700, 300, testOrders.get(1), minQuOrder);
        verify(eventPublisher).publish(new OrderExecutedEvent(1, 500, List.of(new TradeDTO(trade1), new TradeDTO(trade2))));
    }
    @Test
    void new_sell_iceberg_minQu_order_rejected_in_middle_of_buy_queue() {
        createTestOrders(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 1000, 1700, 2, shareholder.getShareholderId(), 100, 700));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 500, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 500)).isEqualTo(null);
    }

    @Test
    void new_buy_iceberg_minQu_order_remains_in_queue_when_minimumExecution_is_Satisfied() {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 20000, 1700, 2, shareholder.getShareholderId(), 500, 500));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1 , 500));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 500)).isNotEqualTo(null);
    }

    @Test
    void new_sell_iceberg_minQu_order_rejected_with_no_matching() {
        Order incomingBuyOrder = new Order(200, security, Side.BUY, 300, 15000, broker2, shareholder);
        security.getOrderBook().enqueue(incomingBuyOrder);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.SELL, 300, 16000, 2, shareholder.getShareholderId(), 50, 100));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 300, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.SELL, 300)).isEqualTo(null);
    }

    @Test
    void new_buy_iceberg_minQu_order_rejected_in_middle_of_sell_queue() {
        createTestOrders(Side.SELL);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.BUY, 1000, 1700, 2, shareholder.getShareholderId(), 500, 700));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 500, List.of(Message.MINIMUM_EXECUTION_QUANTITY_NOT_SATISFIED)));
        assertThat(security.getOrderBook().findByOrderId(Side.BUY, 500)).isEqualTo(null);
    }
}