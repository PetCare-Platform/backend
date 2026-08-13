package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponQueueProcessor;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponQueueService;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

@SpringBootTest
@Transactional
class RedisCouponQueueProcessorTest {

    @Autowired
    private RedisCouponQueueProcessor queueProcessor;

    @Autowired
    private RedisCouponQueueService queueService;

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

    private Long couponId;

    private Long user1Id;
    private Long user2Id;
    private Long user3Id;

    private List<Long> userIds;
    

    @BeforeEach
    void setUp() {

        // Coupon 테스트 데이터 생성
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


        // User 테스트 데이터 생성
        userIds = new ArrayList<>();

        for (int i = 1; i <= 20; i++) {
            User user = userRepository.save(
                    User.builder()
                            .loginId("user" + i)
                            .name("사용자" + i)
                            .email("user" + i + "@test.com")
                            .build()
            );

            userIds.add(user.getId());
            
            // 첫 번째 테스트에서 사용할 사용자 ID
            if (i == 1) {
                user1Id = user.getId();
            } else if (i == 2) {
                user2Id = user.getId();
            } else if (i == 3) {
                user3Id = user.getId();
            }
        }


        // Redis 테스트 데이터 초기화
        queueService.clear(couponId);
        stockService.delete(couponId);


        // Redis 재고 3개 설정
        redisTemplate.opsForValue().set(stockService.getKey(couponId), "3");
    }


    @Test
    void A_B_C_순서대로_Queue를_처리하고_DB에_저장한다() {

        // Given
        queueService.enqueue(
                couponId,
                user1Id,
                "request-A"
        );

        queueService.enqueue(
                couponId,
                user2Id,
                "request-B"
        );

        queueService.enqueue(
                couponId,
                user3Id,
                "request-C"
        );


        // Queue에 A → B → C 순서로 들어갔는지 확인
        String firstRequest = queueService.getFirstRequest(couponId);

        System.out.println("===== 처리 전 =====");
        System.out.println("Redis Stock = " + redisTemplate.opsForValue().get(stockService.getKey(couponId)));
        System.out.println("First Request = " + firstRequest);


        // When
        queueProcessor.processAll(couponId);


        // Then
        // Redis 재고가 0인지 확인
        Long remainingStock = stockService.getRemainingStock(couponId);
        assertThat(remainingStock).isZero();

        // Queue가 완전히 비었는지 확인
        String remainingRequest = queueService.getFirstRequest(couponId);
        assertThat(remainingRequest).isNull();


        // DB에 3건 저장됐는지 확인
        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);
        assertThat(issues).hasSize(3);


        // DB 저장 순서가 A → B → C인지 확인
        assertThat(issues)
                .extracting(issue -> issue.getUser().getId())
                .containsExactly(
                        user1Id,
                        user2Id,
                        user3Id
                );


        // requestId도 A → B → C인지 확인
        assertThat(issues)
                .extracting(CouponIssue::getRequestId)
                .containsExactly(
                        "request-A",
                        "request-B",
                        "request-C"
                );


        // 처리 후 상태 출력
        System.out.println("===== 처리 후 =====");
        System.out.println("Redis Stock = " + remainingStock);
        System.out.println("First Request = " + remainingRequest);
        System.out.println("DB Issue Count = " + issues.size());
        System.out.println("DB Request Order = " + issues.stream().map(CouponIssue::getRequestId).toList());
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

                    queueService.enqueue(
                            couponId,
                            userIds.get(index),
                            "request-" + index
                    );
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();

        // 20개의 요청을 동시에 Queue에 등록
        start.countDown();

        done.await();

        executor.shutdown();

        // Queue의 요청을 처리
        queueProcessor.processAll(couponId);

        // Then
        Long remainingStock = stockService.getRemainingStock(couponId);

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== 동시 요청 테스트 결과 =====");
        System.out.println("요청 수 = " + threadCount);
        System.out.println("Redis Stock = " + remainingStock);
        System.out.println("DB Issue Count = " + issues.size());

        // 재고가 3개이므로 정확히 3개만 발급
        assertThat(issues).hasSize(3);

        // Redis 재고도 0
        assertThat(remainingStock).isZero();
    }
}