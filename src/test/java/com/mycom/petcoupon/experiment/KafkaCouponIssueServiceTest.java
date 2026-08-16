package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.service.KafkaCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

@SpringBootTest
class KafkaCouponIssueServiceTest {

    @Autowired
    private KafkaCouponIssueServiceImpl kafkaCouponIssueService;

    @Autowired
    private RedisCouponStockService stockService;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private CouponIssueRepository couponIssueRepository;

    @Autowired
    private CouponStockRepository couponStockRepository;

    private Long couponId;

    private List<Long> userIds;

    @BeforeEach
    void setUp() {

        couponIssueRepository.deleteAll();

        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        Coupon coupon = couponRepository.save(
                Coupon.builder()
                        .name("테스트 쿠폰")
                        .issueStartAt(LocalDateTime.now().minusMinutes(1))
                        .issueEndAt(LocalDateTime.now().plusMinutes(10))
                        .limitPerMember(1)
                        .status("OPEN")
                        .build()
        );

        couponId = coupon.getId();

        couponStockRepository.save(
                CouponStock.builder()
                        .couponId(couponId)
                        .quantity(3)
                        .build()
        );

        userIds = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            User user = userRepository.save(
                    User.builder()
                            .loginId("kafka-user" + i)
                            .name("사용자" + i)
                            .email("kafka-user" + i + "@test.com")
                            .build()
            );

            userIds.add(user.getId());
        }

        redisTemplate.opsForValue()
                .set(stockService.getKey(couponId), "3");
    }

    // Consumer의 비동기 DB 저장/보상을 기다리는 폴링 헬퍼 (최대 5초, 100ms 간격)
    private void awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("조건이 5초 내에 충족되지 않았습니다.");
    }

    @Test
    void 동시_요청에서_재고보다_많이_발급되지_않는다() throws Exception {

        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();

                    kafkaCouponIssueService.issue(
                            couponId,
                            new CouponIssueRequest(
                                    userIds.get(index),
                                    "kafka-request-" + index
                            )
                    );

                } catch (GeneralException e) {

                    assertThat(e.getErrorCode()).isEqualTo(ExperimentErrorCode.SOLD_OUT);

                } catch (Exception e) {

                    throw new RuntimeException(e);

                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();

        executor.shutdown();

        // Redis 차감은 동기라 즉시 확정됨
        Long remainingStock = stockService.getRemainingStock(couponId);
        assertThat(remainingStock).isZero();

        // Consumer의 비동기 DB 저장이 끝날 때까지 대기
        awaitUntil(() -> couponIssueRepository.countByCoupon_Id(couponId) == 3);

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== Kafka 동시성 테스트 =====");
        System.out.println("전체 요청 수 = " + threadCount);
        System.out.println("최초 재고 = 3");
        System.out.println("남은 Redis 재고 = " + remainingStock);
        System.out.println("실제 발급 수 = " + issues.size());

        assertThat(issues).hasSize(3);
    }

    @Test
    void 동일한_requestId가_동시에_요청되면_한번만_발급된다() throws Exception {

        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        List<GeneralException> exceptions = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {

            int index = i;

            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();

                    kafkaCouponIssueService.issue(
                            couponId,
                            new CouponIssueRequest(
                                    userIds.get(index),
                                    "same-kafka-request-id"
                            )
                    );

                } catch (GeneralException e) {

                    synchronized (exceptions) {
                        exceptions.add(e);
                    }

                } catch (Exception e) {

                    throw new RuntimeException(e);

                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();

        executor.shutdown();

        Long remainingStock = stockService.getRemainingStock(couponId);
        assertThat(remainingStock).isEqualTo(2);

        awaitUntil(() -> couponIssueRepository.countByCoupon_Id(couponId) == 1);

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== 동일 requestId 동시성 테스트 (Kafka) =====");
        System.out.println("실제 발급 수 = " + issues.size());
        System.out.println("남은 Redis 재고 = " + remainingStock);

        assertThat(issues).hasSize(1);
        assertThat(exceptions).hasSize(19);
        assertThat(exceptions)
                .allMatch(e -> e.getErrorCode().equals(ExperimentErrorCode.DUPLICATE_REQUEST));
    }

    @Test
    void 동일한_사용자가_동시에_요청하면_한번만_발급된다() throws Exception {

        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready = new CountDownLatch(threadCount);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threadCount);

        List<GeneralException> exceptions = new ArrayList<>();

        Long userId = userIds.get(0);

        for (int i = 0; i < threadCount; i++) {

            int index = i;

            executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();

                    kafkaCouponIssueService.issue(
                            couponId,
                            new CouponIssueRequest(
                                    userId,
                                    "kafka-request-" + index
                            )
                    );

                } catch (GeneralException e) {

                    synchronized (exceptions) {
                        exceptions.add(e);
                    }

                } catch (Exception e) {

                    throw new RuntimeException(e);

                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();

        executor.shutdown();

        Long remainingStock = stockService.getRemainingStock(couponId);
        assertThat(remainingStock).isEqualTo(2);

        awaitUntil(() -> couponIssueRepository.countByCoupon_Id(couponId) == 1);

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== 동일 사용자 동시성 테스트 (Kafka) =====");
        System.out.println("실제 발급 수 = " + issues.size());
        System.out.println("남은 Redis 재고 = " + remainingStock);

        assertThat(issues).hasSize(1);
        assertThat(exceptions).hasSize(19);
    }

    @Test
    void 존재하지_않는_유저로_DB_저장이_실패하면_Redis_재고가_복구된다() throws Exception {

        Long invalidUserId = 999999L;
        String requestId = "kafka-restore-test-request";

        CouponIssueRequest request = new CouponIssueRequest(invalidUserId, requestId);

        assertThat(stockService.getRemainingStock(couponId)).isEqualTo(3);

        // Kafka 전략은 decreaseStock 단계에서 유저 존재 여부를 검증하지 않으므로 예외 없이 WAITING이 반환됨
        kafkaCouponIssueService.issue(couponId, request);

        // 즉시 차감은 확정됨
        assertThat(stockService.getRemainingStock(couponId)).isEqualTo(2);

        // Consumer가 FK 제약 위반으로 저장 실패 → 재고 보상까지 완료될 때까지 대기
        awaitUntil(() -> stockService.getRemainingStock(couponId) == 3);

        System.out.println("===== Kafka DB 저장 실패 보상 테스트 =====");
        System.out.println("최초 Redis 재고 = 3");
        System.out.println("보상 후 Redis 재고 = " + stockService.getRemainingStock(couponId));

        assertThat(
                redisTemplate.hasKey(stockService.getRequestKey(couponId, requestId))
        ).isFalse();

        assertThat(
                redisTemplate.hasKey(stockService.getUserKey(couponId, invalidUserId))
        ).isFalse();

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);
        assertThat(issues).isEmpty();
    }
}
