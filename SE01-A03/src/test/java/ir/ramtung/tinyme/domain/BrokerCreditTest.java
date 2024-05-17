package ir.ramtung.tinyme.domain;
import ir.ramtung.tinyme.config.MockedJMSTestConfig;
import ir.ramtung.tinyme.domain.entity.*;
import ir.ramtung.tinyme.domain.service.Matcher;
import ir.ramtung.tinyme.messaging.exception.InvalidRequestException;
import ir.ramtung.tinyme.messaging.request.DeleteOrderRq;
import ir.ramtung.tinyme.messaging.request.EnterOrderRq;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static ir.ramtung.tinyme.domain.entity.MatchingOutcome.NOT_ENOUGH_CREDIT;

@SpringBootTest
@Import(MockedJMSTestConfig.class)
@DirtiesContext
public class BrokerCreditTest {
    private Security security;
    private Broker broker;
    private Shareholder shareholder;
    private OrderBook orderBook;
    private List<Order> orders;
    @Autowired
    private Matcher matcher;

    @BeforeEach
    void setupOrderBook() {
        security = Security.builder().build();
        broker = Broker.builder().credit(100_000_000L).build();
        shareholder = Shareholder.builder().build();
        shareholder.incPosition(security, 100_000);
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
            new Order(1, security, Side.BUY, 304, 15700, broker, shareholder),
            new Order(2, security, Side.BUY, 43, 15500, broker, shareholder),
            new Order(3, security, Side.BUY, 445, 15450, broker, shareholder),
            new Order(4, security, Side.BUY, 526, 15450, broker, shareholder),
            new Order(5, security, Side.BUY, 1000, 15400, broker, shareholder),
            new Order(6, security, Side.SELL, 350, 15800, broker, shareholder),
            new Order(7, security, Side.SELL, 285, 15810, broker, shareholder),
            new Order(8, security, Side.SELL, 800, 15810, broker, shareholder),
            new Order(9, security, Side.SELL, 340, 15820, broker, shareholder),
            new Order(10, security, Side.SELL, 65, 15820, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));
    }

    @Test
    void new_sell_order_matches_completely_with_part_of_the_first_buy() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.SELL, 100, 15600, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000);  // money decresed before
        assertThat(broker2.getCredit()).isEqualTo(100000000 + 15700 * 100 ); 
    }

    @Test
    void new_sell_order_matches_partially_with_the_first_buy() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.SELL, 500, 15600, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000); // money decresed before
        assertThat(broker2.getCredit()).isEqualTo(100000000 + 15700 * 304);  
    }

    @Test
    void new_sell_order_matches_partially_with_two_buys() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.SELL, 500, 15500, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000); // money decresed before
        assertThat(broker2.getCredit()).isEqualTo(100000000 + 15700 * 304 + 15500 * 43);
    }

    @Test
    void new_buy_order_matches_with_entire_queue_and_goes_in_order_book() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15820, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000 + (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820)); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820) - (2000 -(350 + 285 + 800 + 340 + 65)) * 15820);
    }

    @Test
    void new_buy_order_does_not_match_with_any_order_and_goes_in_order_book() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15500, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000);
        assertThat(broker2.getCredit()).isEqualTo(100000000 - 2000 * 15500);
    }

    @Test
    void new_buy_order_broker_has_no_money() {
        broker = Broker.builder().credit(0).build();
        Order order = new Order(11, security, Side.BUY, 100, 15600, broker, shareholder);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(NOT_ENOUGH_CREDIT);
        assertThat(broker.getCredit()).isEqualTo(0);
    }

    @Test
    void new_sell_order_matches_partially_with_two_buys_and_rollsback() {
        broker = Broker.builder().credit( 15700 * 304 + 10 * 15500).build();
        Order order = new Order(11, security, Side.BUY, 500, 15500, broker, shareholder);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(NOT_ENOUGH_CREDIT);
        assertThat(broker.getCredit()).isEqualTo(15700 * 304 + 10 * 15500); 
    }

    @Test
    void new_buy_order_matches_with_entire_queue_and_rolesback() {
        broker = Broker.builder().credit(350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15820, broker, shareholder);
        MatchResult result = matcher.execute(order);
        assertThat(result.outcome()).isEqualTo(NOT_ENOUGH_CREDIT);
        assertThat(broker.getCredit()).isEqualTo(350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820); 
    }

    @Test
    void delete_buy_order_gets_back_money_from_queued_order() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15820, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000 + (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820)); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820) - (2000 -(350 + 285 + 800 + 340 + 65)) * 15820);
        assertThatNoException().isThrownBy(()->security.deleteOrder(new DeleteOrderRq(1, security.getIsin(), order.getSide(), order.getOrderId())));
        assertThat(broker.getCredit()).isEqualTo(100000000 + (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820)); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820));
    }

    @Test
    void update_buy_order_that_keeps_priority() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15000, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - 2000 * 15000);
        assertThatNoException().isThrownBy(()->security.updateOrder(EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 1990, 15100, 0, 0, 0),matcher));
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - 1990 * 15100);
    }

    @Test
    void update_buy_order_that_keeps_priority_but_low_credit_and_rollsback() throws InvalidRequestException {
        Broker broker2 = Broker.builder().credit(2000 * 15000).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15000, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(0);
        MatchResult result = security.updateOrder(EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 2000, 15100, 0, 0, 0),matcher);
            assertThat(result.outcome()).isEqualTo(NOT_ENOUGH_CREDIT);
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(0);
    }

    @Test
    void update_buy_order_that_increases_priority() {
        Broker broker2 = Broker.builder().credit(100_000_000L).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15000, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - 2000 * 15000);
        assertThatNoException().isThrownBy(()->security.updateOrder(EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 2000, 17000, 0, 0, 0),matcher));
        assertThat(broker.getCredit()).isEqualTo(100000000 + (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820)); 
        assertThat(broker2.getCredit()).isEqualTo(100000000 - (350 * 15800 + 285 * 15810 + 800 * 15810 + 340 * 15820 + 65 * 15820) - (2000 -(350 + 285 + 800 + 340 + 65)) * 17000);
    }

    @Test
    void update_buy_order_that_increases_priority_with_low_credit_rollsback() throws InvalidRequestException {
        Broker broker2 = Broker.builder().credit(2000 * 15000).build();
        Order order = new Order(11, security, Side.BUY, 2000, 15000, broker2, shareholder);
        matcher.execute(order);
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(0);
        MatchResult result = security.updateOrder(EnterOrderRq.createUpdateOrderRq(1, security.getIsin(), 11, LocalDateTime.now(), Side.BUY, 2000, 17000, 0, 0, 0),matcher);
        assertThat(result.outcome()).isEqualTo(NOT_ENOUGH_CREDIT);
        assertThat(broker.getCredit()).isEqualTo(100000000); 
        assertThat(broker2.getCredit()).isEqualTo(0);
    }


    @Test
    void iceberg_order_in_queue_matched_completely_after_three_rounds() {
        security = Security.builder().build();
        broker = Broker.builder().build();
        orderBook = security.getOrderBook();
        orders = Arrays.asList(
                new IcebergOrder(1, security, Side.BUY, 450, 15450, broker, shareholder, 200),
                new Order(2, security, Side.BUY, 70, 15450, broker, shareholder),
                new Order(3, security, Side.BUY, 1000, 15400, broker, shareholder)
        );
        orders.forEach(order -> orderBook.enqueue(order));
        Order order = new Order(4, security, Side.SELL, 600, 15450, broker, shareholder);
        matcher.execute(order);

        assertThat(broker.getCredit()).isEqualTo(200 * 15450 + 70 * 15450 + 200 * 15450 + 50 * 15450 );
    }

}
