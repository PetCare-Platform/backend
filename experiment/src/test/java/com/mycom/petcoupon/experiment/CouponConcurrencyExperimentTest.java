package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponStatusResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CreateCouponRequest;
import com.mycom.petcoupon.experiment.coupon.service.ExperimentCouponService;
import com.mycom.petcoupon.experiment.coupon.service.PessimisticCouponIssueService;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_CONCURRENCY_TESTS", matches = "true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CouponConcurrencyExperimentTest {

    private static final int QUANTITY = 100;
    private static final int REQUEST_COUNT = 200;
    private static final int WORKER_COUNT = 200;

    @Autowired
    private ExperimentCouponService experimentCouponService;

    @Autowired
    private PessimisticCouponIssueService pessimisticCouponIssueService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeAll
    void prepareUsers() {
        List<Object[]> users = new ArrayList<>(REQUEST_COUNT);
        for (long userId = 1; userId <= REQUEST_COUNT; userId++) {
            users.add(new Object[] {
                    userId,
                    "load-user-" + userId,
                    "load-user-" + userId,
                    "load-user-" + userId + "@example.com"
            });
        }

        jdbcTemplate.batchUpdate("""
                INSERT IGNORE INTO app_user (user_id, login_id, name, email)
                VALUES (?, ?, ?, ?)
                """, users);
    }

    @Test
    void pessimisticLockIssuesExactlyTheAvailableQuantity() throws InterruptedException {
        Long couponId = experimentCouponService.create(CreateCouponRequest.builder()
                .quantity(QUANTITY)
                .build()).couponId();

        ConcurrentResult result = execute(couponId);
        CouponStatusResponse status = experimentCouponService.getStatus(couponId);

        assertThat(result.successCount()).isEqualTo(QUANTITY);
        assertThat(result.failureCount()).isEqualTo(REQUEST_COUNT - QUANTITY);
        assertThat(status.dbRemainingQuantity()).isZero();
        assertThat(status.issueCount()).isEqualTo(QUANTITY);
        assertThat(status.consistent()).isTrue();
    }

    private ConcurrentResult execute(Long couponId) throws InterruptedException {
        ExecutorService executor = Executors.newFixedThreadPool(WORKER_COUNT);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(REQUEST_COUNT);
        AtomicInteger successCount = new AtomicInteger();
        AtomicInteger failureCount = new AtomicInteger();

        try {
            for (long userId = 1; userId <= REQUEST_COUNT; userId++) {
                long requestUserId = userId;
                executor.submit(() -> {
                    try {
                        start.await();
                        pessimisticCouponIssueService.issue(
                                couponId,
                                CouponIssueRequest.builder()
                                        .userId(requestUserId)
                                        .requestId("pessimistic-" + couponId + "-" + requestUserId)
                                        .build());
                        successCount.incrementAndGet();
                    } catch (Exception exception) {
                        failureCount.incrementAndGet();
                    } finally {
                        done.countDown();
                    }
                });
            }

            start.countDown();
            boolean completed = done.await(
                    Duration.ofMinutes(2).toMillis(),
                    TimeUnit.MILLISECONDS);
            assertThat(completed).as("all concurrent requests completed").isTrue();
        } finally {
            executor.shutdownNow();
        }

        return new ConcurrentResult(successCount.get(), failureCount.get());
    }

    private record ConcurrentResult(int successCount, int failureCount) {
    }
}
