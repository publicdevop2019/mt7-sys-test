package com.hw.chaos;

import com.hw.helper.*;
import com.jayway.jsonpath.JsonPath;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.IntStream;

import static com.hw.integration.profile.OrderTest.getOrderId;

@Slf4j
/**
 * create clean env before test
 * UPDATE product_detail SET order_storage = 1000;
 * UPDATE product_detail SET actual_storage = 500;
 * UPDATE product_detail SET sales = NULL ;
 * DELETE FROM change_record ;
 */
@Component
public class ChaosTest {
    @Autowired
    UserAction action;
    @Autowired
    TestHelper helper;

    UUID uuid;

    private void setUp() {
        uuid = UUID.randomUUID();
        action.restTemplate.getRestTemplate().setInterceptors(Collections.singletonList(new OutgoingReqInterceptor(uuid)));
    }

    /**
     * concurrent_create_order
     * randomly_pay
     * randomly_replace
     * after some time validate order storage actually storage
     */
    public void testCase1() {
        int numOfConcurrent = 10;
        setUp();
        Runnable runnable = new Runnable() {
            @SneakyThrows
            @Override
            public void run() {
                Thread.sleep(5000);//give some delay
                // randomly pick test user
                log.info("thread start");
                ArrayList<Integer> integers4 = new ArrayList<>();
                integers4.add(200);
                integers4.add(500);
                ResourceOwner user = action.testUser.get(new Random().nextInt(action.testUser.size()));
                String defaultUserToken = action.getJwtPassword(user.getEmail(), user.getPassword()).getBody().getValue();
                log.info("user token " + defaultUserToken);
                OrderDetail orderDetail1 = action.createOrderDetailForUser(defaultUserToken);
                log.info("draft order created");
                String url3 = helper.getUserProfileUrl("/orders/user");
                ResponseEntity<String> exchange = action.restTemplate.exchange(url3, HttpMethod.POST, action.getHttpRequestAsString(defaultUserToken, orderDetail1), String.class);
                log.info("create status code " + exchange.getStatusCode());
                Assert.assertTrue("create success or concurrent-failure", integers4.contains(exchange.getStatusCode().value()));
                Thread.sleep(10000);//wait for order creation
                int randomValue = new Random().nextInt(20);
                String orderId = getOrderId(exchange.getHeaders().getLocation().toString());
                if (randomValue < 10) {

                    if (exchange.getStatusCode().is2xxSuccessful()) {
                        //randomly pay
                        if (randomValue < 5) {
                            log.info("randomly pay");
                            Assert.assertNotNull(exchange.getHeaders().getLocation().toString());
                            String url4 = helper.getUserProfileUrl("/orders/user/" + orderId + "/confirm");
                            ResponseEntity<String> exchange7 = action.restTemplate.exchange(url4, HttpMethod.PUT, action.getHttpRequest(defaultUserToken), String.class);
                            Assert.assertEquals(HttpStatus.OK, exchange7.getStatusCode());
                            Boolean read = JsonPath.read(exchange7.getBody(), "$.paymentStatus");
                            Assert.assertEquals(true, read);

                        } else {
                            log.info("randomly update address");
                            String url5 = helper.getUserProfileUrl("/orders/user/" + orderDetail1.getId());
                            Address address = new Address();
                            address.setCountry("testCountry");
                            address.setProvince("testProvince");
                            address.setCity("testCity");
                            address.setLine1("testLine1");
                            address.setLine2("testLine2");
                            address.setPostalCode("testPostalCode");
                            address.setPhoneNumber("testPhoneNumber");
                            address.setFullName("testFullName");
                            ResponseEntity<String> exchange5 = action.restTemplate.exchange(url5, HttpMethod.PUT, action.getHttpRequestAsString(defaultUserToken, address), String.class);
                        }
                    }
                } else if (randomValue < 15) {
                    log.info("randomly replace");
                    // randomly replace order, regardless it's state
                    String url4 = helper.getUserProfileUrl("/orders/user");
                    ResponseEntity<SumTotalOrder> exchange3 = action.restTemplate.exchange(url4, HttpMethod.GET, action.getHttpRequest(defaultUserToken), SumTotalOrder.class);
                    List<OrderDetail> body = exchange3.getBody().getData();
                    if (body != null) {
                        int size = body.size();
                        if (size > 0) {
                            OrderDetail orderDetail = body.get(new Random().nextInt(size));
                            String url8 = helper.getUserProfileUrl("/orders/user/" + orderDetail.getId());
                            ResponseEntity<OrderDetail> exchange8 = action.restTemplate.exchange(url8, HttpMethod.GET, action.getHttpRequest(defaultUserToken), OrderDetail.class);
                            Assert.assertEquals(HttpStatus.OK, exchange8.getStatusCode());
                            OrderDetail body1 = exchange8.getBody();

                            String url5 = helper.getUserProfileUrl("/orders/user/" + orderDetail.getId() + "/reserve");
                            ResponseEntity<String> exchange5 = action.restTemplate.exchange(url5, HttpMethod.PUT, action.getHttpRequestAsString(defaultUserToken, body1), String.class);
                            ArrayList<Integer> integers2 = new ArrayList<>();
                            integers2.add(200);
                            integers2.add(400);
                            integers2.add(500);
                            Assert.assertTrue("replace success or done by other thread", integers2.contains(exchange5.getStatusCode().value()));
                            if (randomValue <= 13 && exchange5.getStatusCode().value() == 200) {
                                log.info("after replace, directly pay");
                                // after replace, directly pay
                                String url6 = helper.getUserProfileUrl("/orders/user/" + orderDetail.getId() + "/confirm");
                                ResponseEntity<String> exchange7 = action.restTemplate.exchange(url6, HttpMethod.PUT, action.getHttpRequest(defaultUserToken), String.class);
                                log.info("exchange7.getBody() {}", exchange7.getBody());
                                ArrayList<Integer> integers = new ArrayList<>();
                                integers.add(200);
                                integers.add(400);
                                integers.add(500);
                                Assert.assertTrue("order pay success or already paid", integers.contains(exchange7.getStatusCode().value()));
                                Boolean read = JsonPath.read(exchange7.getBody(), "$.paymentStatus");
                                Assert.assertEquals(true, read);
                            } else {
                                log.info("after replace, not pay");
                                // after replace, not pay

                            }

                        }
                    }
                    log.info("no pending order");
                } else {
                    if (randomValue > 18) {
                        log.info("randomly not pay");
                    } else {
                        log.info("randomly delete");
                        String url6 = helper.getUserProfileUrl("/orders/user/" + orderId);
                        action.restTemplate.exchange(url6, HttpMethod.DELETE, action.getHttpRequest(defaultUserToken), String.class);
                    }
                }
            }
        };
        ArrayList<Runnable> runnables = new ArrayList<>();
        IntStream.range(0, numOfConcurrent).forEach(e -> {
            runnables.add(runnable);
        });
        try {
            UserAction.assertConcurrent("", runnables, 300000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }
}
