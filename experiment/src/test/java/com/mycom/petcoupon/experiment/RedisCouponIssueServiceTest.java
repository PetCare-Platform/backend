package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
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
        
        couponStockRepository.save(
        		CouponStock.builder()
	                .couponId(couponId)
	                .quantity(3)
	                .build()
        );

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

                    redisCouponIssueService.issue(
                            couponId,
                            new CouponIssueRequest(
                                    userIds.get(index),
                                    "same-request-id"
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

        List<CouponIssue> issues = couponIssueRepository
        		.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== 동일 requestId 동시성 테스트 =====");
        System.out.println("전체 요청 수 = " + threadCount);
        System.out.println("실제 발급 수 = " + issues.size());
        System.out.println("남은 Redis 재고 = " + remainingStock);

        assertThat(issues).hasSize(1);

        assertThat(remainingStock).isEqualTo(2);

        assertThat(exceptions).hasSize(19);

        assertThat(exceptions)
                .allMatch(e ->
                        e.getErrorCode().equals(ExperimentErrorCode.DUPLICATE_REQUEST)
                );
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

                    redisCouponIssueService.issue(
                            couponId,
                            new CouponIssueRequest(
                                    userId,
                                    "request-" + index
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

        List<CouponIssue> issues = couponIssueRepository.findAllByCoupon_IdOrderByIdAsc(couponId);

        System.out.println("===== 동일 사용자 동시성 테스트 =====");
        System.out.println("전체 요청 수 = " + threadCount);
        System.out.println("실제 발급 수 = " + issues.size());
        System.out.println("남은 Redis 재고 = " + remainingStock);
        System.out.println("실패 요청 수 = " + exceptions.size());

        assertThat(issues).hasSize(1);

        assertThat(remainingStock).isEqualTo(2);

        assertThat(exceptions).hasSize(19);
    }
    
    @Test
    void DB_저장_실패시_Redis_상태가_복구된다() {

        // given
        Long invalidUserId = 999999L;
        String requestId = "restore-test-request";

        CouponIssueRequest request = new CouponIssueRequest(invalidUserId, requestId);

        // 초기 재고
        assertThat(stockService.getRemainingStock(couponId)).isEqualTo(3);

        // when
        assertThatThrownBy(() ->
                redisCouponIssueService.issue(couponId, request)
        );

        // then
        Long remainingStock = stockService.getRemainingStock(couponId);

        System.out.println("===== DB 저장 실패 보상 테스트 =====");
        System.out.println("최초 Redis 재고 = 3");
        System.out.println("실패 후 Redis 재고 = " + remainingStock);

        // Redis 재고 복구
        assertThat(remainingStock).isEqualTo(3);

        // requestId 키 삭제
        assertThat(
                redisTemplate.hasKey(
                        stockService.getRequestKey(couponId, requestId)
                )
        ).isFalse();

        // user 키 삭제
        assertThat(
                redisTemplate.hasKey(
                        stockService.getUserKey(couponId, invalidUserId)
                )
        ).isFalse();

        // DB 발급 이력 없음
        List<CouponIssue> issues = couponIssueRepository
        		.findAllByCoupon_IdOrderByIdAsc(couponId);

        assertThat(issues).isEmpty();
    }
    
    @Test
    void Redis_초기화시_쿠폰의_중복방지_키도_삭제된다() {

        // given
        Long userId = userIds.get(0);
        String requestId = "init-test-request";

        // Redis에 기존 상태 생성
        redisTemplate.opsForValue().set(stockService.getKey(couponId), "2");

        redisTemplate.opsForValue()
                .set(
                        stockService.getRequestKey(couponId, requestId),
                        requestId
                );

        redisTemplate.opsForValue()
                .set(
                        stockService.getUserKey(couponId, userId),
                        String.valueOf(userId)
                );

        // when
        stockService.initialize(couponId);

        // then
        assertThat(
                redisTemplate.hasKey(
                        stockService.getRequestKey(couponId, requestId)
                )
        ).isFalse();

        assertThat(
                redisTemplate.hasKey(
                        stockService.getUserKey(couponId, userId)
                )
        ).isFalse();

        // DB 재고 기준으로 다시 초기화됐는지 확인
        Long remainingStock = stockService.getRemainingStock(couponId);

        assertThat(remainingStock).isEqualTo(3);
    }
}