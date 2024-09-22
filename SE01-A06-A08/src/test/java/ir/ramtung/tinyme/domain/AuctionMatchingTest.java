package ir.ramtung.tinyme.domain;

import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.MatchingStateHandler;
import ir.ramtung.tinyme.domain.service.OrderHandler;
import ir.ramtung.tinyme.messaging.EventPublisher;
import ir.ramtung.tinyme.messaging.Message;
import ir.ramtung.tinyme.messaging.event.*;
import ir.ramtung.tinyme.messaging.request.ChangeMatchingStateRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;
import ir.ramtung.tinyme.messaging.request.MatchingState;
import ir.ramtung.tinyme.repository.BrokerRepository;
import ir.ramtung.tinyme.repository.SecurityRepository;
import ir.ramtung.tinyme.repository.ShareholderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import java.time.LocalDateTime;
import java.util.List;
@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class AuctionMatchingTest {

    @Autowired
    OrderHandler orderHandler;
    @Autowired
    MatchingStateHandler matchingStateHandler;
    @Autowired
    EventPublisher eventPublisher;
    @Autowired
    SecurityRepository securityRepository;
    @Autowired
    BrokerRepository brokerRepository;
    @Autowired
    ShareholderRepository shareholderRepository;
    private Security security;
    private Shareholder shareholder1 , shareholder2;
    private Broker broker1, broker2, broker3;

    private List<Order> testOrders;

    public Pair<Order, EnterOrderRq> makePairOrderAndOrderRq(long requestId, long orderId, Side side, int quantity, int price, int stopPrice) {
        EnterOrderRq rq = EnterOrderRq.createNewOrderRq(requestId, "ABC", orderId, LocalDateTime.now(), side, quantity, price, 2, shareholder1.getShareholderId(), 0, 0, stopPrice);
        Order order = new Order(orderId, security, side, quantity, price, broker2, shareholder1);
        return new Pair<>(order, rq);
    }

    List<Order> createTestOrders1(Side side)
    {
        testOrders = List.of(
                new Order(1, security,side, 300, 1600, broker2, shareholder2),
                new Order(2, security,side, 300, 1700, broker2, shareholder2),
                new Order(3, security,side, 300, 1800, broker2, shareholder2)
        );
        enqueueTestOrders();
        return testOrders;
    }

    List<Order> createTestOrders2(Side side)
    {
        testOrders = List.of(
                new Order(4, security,side, 1500, 2200, broker3, shareholder1),
                new Order(5, security,side, 1500, 3600, broker3, shareholder1),
                new Order(6, security,side, 7000, 1300, broker3, shareholder1),
                new Order(7, security,side.opposite(), 700, 2100, broker1, shareholder2),
                new Order(8, security,side.opposite(), 3000, 4000, broker1, shareholder2)
        );
        enqueueTestOrders();
        return testOrders;
    }

    List<Order> createIcebergTestOrders(Side side){
        testOrders = List.of(
                new IcebergOrder(10, security,side, 1000, 1600, broker2, shareholder1 , 100),
                new IcebergOrder(11, security,side, 700, 2000, broker2, shareholder1  , 100),
                new IcebergOrder(12, security,side.opposite(), 500, 1500, broker1, shareholder2 , 200),
                new IcebergOrder(13, security,side.opposite(), 800, 1700, broker1, shareholder2 , 400)
        );
        enqueueTestOrders();
        return testOrders;
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

        shareholder1 = Shareholder.builder().build();
        shareholder2 = Shareholder.builder().build();
        shareholder1.incPosition(security, 1_000_000);
        shareholder2.incPosition(security, 1_000_000);
        shareholderRepository.addShareholder(shareholder1);
        shareholderRepository.addShareholder(shareholder2);


        broker1 = Broker.builder().brokerId(1).credit(100_000_000).build();
        broker2 = Broker.builder().brokerId(2).credit(100_000_000).build();
        broker3 = Broker.builder().brokerId(2).credit(100_000_000).build();
        brokerRepository.addBroker(broker1);
        brokerRepository.addBroker(broker2);
        brokerRepository.addBroker(broker3);
    }

    @Test
    void change_matching_state_from_continues_to_auction()
    {
        createTestOrders1(Side.BUY);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION);
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
    }

    @Test
    void change_matching_state_to_auction_then_back_to_continues()
    {
        createTestOrders1(Side.BUY);
        ChangeMatchingStateRq rq1 = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION);
        matchingStateHandler.handleEnterMatchingState(rq1);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq1.getEntryTime()));
        ChangeMatchingStateRq rq2 = new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS);
        matchingStateHandler.handleEnterMatchingState(rq2);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.CONTINUOUS  ,"ABC" , rq2.getEntryTime()));
    }

    @Test
    void calculate_auction_price_with_only_normal_orders() {
        createTestOrders1(Side.BUY);
        createTestOrders1(Side.SELL);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 1700 , 600) );
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 1000, 1700, 1, shareholder1.getShareholderId(), 0, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 0 , 0) );
    }

    @Test
    void calculate_auction_price_with_iceberg_orders()
    {
        createTestOrders1(Side.BUY);
        createTestOrders1(Side.SELL);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 1700 , 600) );
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 500, LocalDateTime.now(), Side.SELL, 1000, 17000, 1, shareholder1.getShareholderId(), 300, 0));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 0 , 0) );
    }


    @Test
    void opening_price_calculator_returns_zero_tradable_quantity_when_sell_queue_is_empty()
    {
        createTestOrders1(Side.BUY);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 0 , 0) );
    }

    @Test
    void opening_price_calculator_returns_zero_tradable_quantity_when_buy_queue_is_empty()
    {
        createTestOrders1(Side.SELL);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 0 , 0) );
    }

    @Test
    void update_Stoplimit_order_is_rejected_on_auction_mode()
    {
        createTestOrders1(Side.BUY);
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.SELL, 300, 16000, 2, shareholder1.getShareholderId(), 0, 0 , 17000));
        verify(eventPublisher).publish(new OrderAcceptedEvent(1, 300));
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        orderHandler.handleEnterOrder(EnterOrderRq.createUpdateOrderRq(1, "ABC", 300, LocalDateTime.now(), Side.SELL, 300, 16000, 2, shareholder1.getShareholderId(), 0, 18000));
        verify(eventPublisher).publish(new OrderRejectedEvent(1, 300 , List.of(Message.UPDATE_STOP_LIMIT_ORDER_REJECTED_ON_AUCTION_MODE)));

    }

    @Test
    void auction_matcher_matches_normal_orders()
    {
        createTestOrders1(Side.BUY);
        createTestOrders2(Side.SELL);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 1600 , 4600));
        //check trades
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 3000 , 1600 , 8 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 700 , 1600 , 7 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 300 , 1600 , 3 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 300 , 1600 , 2 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 300 , 1600 , 1 , 6));
        //check broker credits
        assertThat(broker1.getCredit()).isEqualTo(94_080_000);
        assertThat(broker2.getCredit()).isEqualTo(98_560_000);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000 + 1600 * 4600);
        assertThat(shareholder2.hasEnoughPositionsOn(security ,1_000_000 + 4600)).isEqualTo(true);
        assertThat(shareholder1.hasEnoughPositionsOn(security ,1000_000 - 4600)).isEqualTo(true);
    }


    @Test
    void auction_matcher_matches_iceberg_orders()
    {
        createIcebergTestOrders(Side.SELL);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 1700, 800));
        //check trades
        verify(eventPublisher , times(8)).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 100 , 1700 , 13 , 10));
        //check brokers and shareholders credit
        assertThat(broker1.getCredit()).isEqualTo(100_000_000 - 1700 * 800);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000 + 1700 * 800);
        assertThat(shareholder2.hasEnoughPositionsOn(security ,1_000_000 + 800)).isEqualTo(true);
        assertThat(shareholder1.hasEnoughPositionsOn(security ,1_000_000 - 800)).isEqualTo(true);
    }

    @Test
    void auction_matcher_matches_normal_and_iceberg_orders()
    {
        createTestOrders1(Side.BUY);
        createTestOrders2(Side.SELL);
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        createIcebergTestOrders(Side.SELL);
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 1500 , 5900));
        //check trades
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 3000 , 1500 , 8 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 700 , 1500 , 7 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 300 , 1500 , 3 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 300 , 1500 , 2 , 6));
        verify(eventPublisher , times(2)).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 400 , 1500 , 13 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 300 , 1500 , 1 , 6));
        verify(eventPublisher , times(2)).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 200 , 1500 , 12 , 6));
        verify(eventPublisher).publish(new TradeEvent(rq.getEntryTime() , "ABC" , 100 , 1500 , 12 , 6));
        //check brokers credit
        assertThat(broker1.getCredit()).isEqualTo(100_000_000 - 1500 * 5000);
        assertThat(broker2.getCredit()).isEqualTo(100_000_000 - 1500 * 900);
        assertThat(broker3.getCredit()).isEqualTo(100_000_000 + 1500 * 5900);
        assertThat(shareholder2.hasEnoughPositionsOn(security ,1_000_000 + 5900)).isEqualTo(true);
        assertThat(shareholder1.hasEnoughPositionsOn(security ,1_000_000 - 5900)).isEqualTo(true);
    }


    @Test
    void opening_price_calculator_returns_zero_when_cannot_match_anything()
    {
        security.getOrderBook().enqueue(new Order(1, security,Side.SELL, 300, 700, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.SELL, 300, 800, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(3, security,Side.SELL, 300, 900, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(4, security,Side.BUY, 300, 400, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(5, security,Side.BUY, 300, 500, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(6, security,Side.BUY, 300, 600, broker2, shareholder1));

        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 0 , 0));
    }

    @Test
    void opening_price_calculator_returns_highest_price_with_maximum_tradable_quantity()
    {
        security.getOrderBook().enqueue(new Order(1, security,Side.BUY, 300, 700, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.BUY, 300, 800, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(3, security,Side.BUY, 300, 900, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(4, security,Side.SELL, 300, 400, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(5, security,Side.SELL, 300, 500, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(6, security,Side.SELL, 300, 600, broker2, shareholder1));

        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 700 , 900));
    }

    @Test
    void opening_price_calculator_returns_best_price_for_maximize_quantity()
    {
        security.getOrderBook().enqueue(new Order(1, security,Side.SELL, 300, 800, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.SELL, 300, 900, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(3, security,Side.BUY, 300, 900, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(4, security,Side.BUY, 300, 900, broker2, shareholder1));

        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 900 , 600));
    }

    @Test
    void opening_price_calculator_returns_nearest_highest_price_to_last_trade_price()
    {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.SELL, 500, 1000, 1, shareholder1.getShareholderId(), 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 1000, 2, shareholder1.getShareholderId(), 0, 0));
        //after above trade , lastPrice = 1000
        security.getOrderBook().enqueue(new Order(1, security,Side.SELL, 300, 800, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.SELL, 300, 900, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(3, security,Side.BUY, 300, 800, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(4, security,Side.BUY, 300, 900, broker2, shareholder1));

        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 900 , 300));
    }

    @Test
    void opening_price_calculator_returns_nearest_lowest_price_to_last_trade_price()
    {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.SELL, 500, 1000, 1, shareholder1.getShareholderId(), 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 1000, 2, shareholder1.getShareholderId(), 0, 0));
        //after above trade , lastPrice = 1000
        security.getOrderBook().enqueue(new Order(1, security,Side.SELL, 300, 1100, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.SELL, 300, 1200, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(3, security,Side.BUY, 300, 1100, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(4, security,Side.BUY, 300, 1200, broker2, shareholder1));

        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 1100 , 300));
    }

    @Test
    void opening_price_calculator_returns_lowest_price_between_prices_near_last_trade_price()
    {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 1, LocalDateTime.now(), Side.SELL, 500, 1000, 1, shareholder1.getShareholderId(), 0, 0));
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(2, "ABC", 2, LocalDateTime.now(), Side.BUY, 500, 1000, 2, shareholder1.getShareholderId(), 0, 0));
        //after above trade , lastPrice = 1000
        security.getOrderBook().enqueue(new Order(1, security,Side.SELL, 300, 1100, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.SELL, 300, 900, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(3, security,Side.BUY, 300, 1100, broker2, shareholder1));
        security.getOrderBook().enqueue(new Order(4, security,Side.BUY, 300, 900, broker2, shareholder1));

        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 900 , 300));
    }

    @Test
    void stoplimit_orders_are_activated_after_finnish_auction_matching()
    {
        orderHandler.handleEnterOrder(EnterOrderRq.createNewOrderRq(1, "ABC", 100, LocalDateTime.now(), Side.SELL, 1000, 1700, 1, shareholder1.getShareholderId(), 0, 0 , 2000));
        security.getOrderBook().enqueue(new Order(1, security,Side.SELL, 300, 2000, broker1, shareholder1));
        security.getOrderBook().enqueue(new Order(2, security,Side.BUY, 300, 2000, broker2, shareholder2));
        ChangeMatchingStateRq rq = new ChangeMatchingStateRq("ABC", MatchingState.AUCTION , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new SecurityStateChangedEvent( MatchingState.AUCTION  ,"ABC" , rq.getEntryTime()));
        verify(eventPublisher).publish(new OpeningPriceEvent(rq.getEntryTime() , "ABC" , 2000 , 300));
        rq = new ChangeMatchingStateRq("ABC", MatchingState.CONTINUOUS , LocalDateTime.now().withNano(0).withSecond(0));
        matchingStateHandler.handleEnterMatchingState(rq);
        verify(eventPublisher).publish(new OrderActivatedEvent(1 , 100));
    }

}
