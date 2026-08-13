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

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.service.RedisCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.global.exception.ExperimentErrorCode;
import com.mycom.petcoupon.experiment.global.exception.GeneralException;
import com.mycom.petcoupon.experiment.issue.entity.CouponIssue;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

@SpringBootTest
class RedisCouponIssueServiceTest {

    @Autowired
    private RedisCouponIssueServiceImpl redisCouponIssueService;

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

    private List<Long> userIds;

    @BeforeEach
    void setUp() {

    	couponIssueRepository.deleteAll();
    	
        // 쿠폰 생성
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

        // 사용자 20명 생성
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
        }

        // Redis 초기화
        stockService.delete(couponId);

        // 재고 3개 설정
        redisTemplate.opsForValue()
                .set(stockService.getKey(couponId), "3");
    }

    @Test
    void 동시_요청에서_재고보다_많이_발급되지_않는다() throws Exception {

        // given
        int threadCount = 20;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);

        CountDownLatch ready =new CountDownLatch(threadCount);

        CountDownLatch start = new CountDownLatch(1);

        CountDownLatch done = new CountDownLatch(threadCount);

        // when
        for (int i = 0; i < threadCount; i++) {
            int index = i;
            executor.submit(() -> {
                try {
                    ready.countDown();

                    // 모든 스레드가 준비될 때까지 대기
                    start.await();

                    // 동시에 쿠폰 발급 요청
                    redisCouponIssueService.issue(
                            couponId,
                            new CouponIssueRequest(
                                    userIds.get(index),
                                    "request-" + index
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

        // 모든 스레드 준비
        ready.await();

        // 동시에 시작
        start.countDown();

        // 모든 요청 완료 대기
        done.await();

        executor.shutdown();

        // then
        Long remainingStock = stockService.getRemainingStock(couponId);

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== Redis 동시성 테스트 =====");
        System.out.println("전체 요청 수 = " + threadCount);
        System.out.println("최초 재고 = 3");
        System.out.println("남은 Redis 재고 = " + remainingStock);
        System.out.println("실제 발급 수 = " + issues.size());

        // 재고 3개이므로 정확히 3개만 발급
        assertThat(issues).hasSize(3);

        // Redis 재고는 0
        assertThat(remainingStock).isZero();
    }
}