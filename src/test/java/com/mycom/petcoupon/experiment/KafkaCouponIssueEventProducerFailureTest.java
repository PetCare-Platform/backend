package com.mycom.petcoupon.experiment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueRequest;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResponse;
import com.mycom.petcoupon.experiment.coupon.dto.CouponIssueResult;
import com.mycom.petcoupon.experiment.coupon.entity.Coupon;
import com.mycom.petcoupon.experiment.coupon.entity.CouponStock;
import com.mycom.petcoupon.experiment.coupon.redis.RedisCouponStockService;
import com.mycom.petcoupon.experiment.coupon.repository.CouponRepository;
import com.mycom.petcoupon.experiment.coupon.repository.CouponStockRepository;
import com.mycom.petcoupon.experiment.coupon.service.KafkaCouponIssueServiceImpl;
import com.mycom.petcoupon.experiment.issue.repository.CouponIssueRepository;
import com.mycom.petcoupon.experiment.kafka.constant.KafkaTopics;
import com.mycom.petcoupon.experiment.kafka.dto.CouponIssueEvent;
import com.mycom.petcoupon.experiment.user.entity.User;
import com.mycom.petcoupon.experiment.user.repository.UserRepository;

// KafkaTemplate 빈만 목으로 교체해 발행 실패를 재현하므로, 실제 Kafka로 발행하는
// KafkaCouponIssueServiceTest와 같은 컨텍스트를 공유하지 않도록 별도 클래스로 분리했다.
@SpringBootTest
class KafkaCouponIssueEventProducerFailureTest {

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

    @MockitoBean
    private KafkaTemplate<String, CouponIssueEvent> couponIssueEventKafkaTemplate;

    private Long couponId;

    private Long userId;

    @BeforeEach
    void setUp() {

        couponIssueRepository.deleteAll();

        redisTemplate.getConnectionFactory()
                .getConnection()
                .serverCommands()
                .flushDb();

        Coupon coupon = couponRepository.save(
                Coupon.builder()
                        .name("발행 실패 보상 테스트 쿠폰")
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

        User user = userRepository.save(
                User.builder()
                        .loginId("producer-fail-user")
                        .name("발행실패유저")
                        .email("producer-fail@test.com")
                        .build()
        );

        userId = user.getId();

        redisTemplate.opsForValue().set(stockService.getKey(couponId), "3");
    }

    @Test
    void 브로커_장애로_발행이_실패하면_Redis_재고와_중복방지_키가_복구된다() {

        String requestId = "producer-fail-request";

        CompletableFuture<SendResult<String, CouponIssueEvent>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new TimeoutException("브로커 응답 없음"));

        when(couponIssueEventKafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), anyString(), any(CouponIssueEvent.class)))
                .thenReturn(failedFuture);

        CouponIssueResponse response = kafkaCouponIssueService.issue(
                couponId, new CouponIssueRequest(userId, requestId));

        // 클라이언트 입장에서는 WAITING을 받지만, 실제로는 발행이 실패해 아무 일도 일어나지 않는 상태
        assertThat(response.result()).isEqualTo(CouponIssueResult.WAITING);

        // 실패한 Future에 대한 whenComplete 콜백은 동기적으로 실행되므로 별도 대기 없이 바로 검증 가능
        assertThat(stockService.getRemainingStock(couponId)).isEqualTo(3);

        assertThat(
                redisTemplate.hasKey(stockService.getRequestKey(couponId, requestId))
        ).isFalse();

        assertThat(
                redisTemplate.hasKey(stockService.getUserKey(couponId, userId))
        ).isFalse();

        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();
    }

    @Test
    void send_호출_자체가_동기_예외를_던지면_Redis_재고와_중복방지_키가_복구되고_호출자에게_예외가_전달된다() {

        String requestId = "producer-sync-fail-request";

        // Future를 반환하기도 전에 send() 호출 자체가 예외를 던지는 상황을 재현
        // (예: 메타데이터 조회 타임아웃, 직렬화 실패 등 KafkaProducer.send()가 동기적으로 던지는 케이스)
        when(couponIssueEventKafkaTemplate.send(eq(KafkaTopics.COUPON_ISSUE_EVENT), anyString(), any(CouponIssueEvent.class)))
                .thenThrow(new KafkaException("브로커에 연결할 수 없음"));

        assertThatThrownBy(() ->
                kafkaCouponIssueService.issue(couponId, new CouponIssueRequest(userId, requestId))
        ).isInstanceOf(KafkaException.class);

        assertThat(stockService.getRemainingStock(couponId)).isEqualTo(3);

        assertThat(
                redisTemplate.hasKey(stockService.getRequestKey(couponId, requestId))
        ).isFalse();

        assertThat(
                redisTemplate.hasKey(stockService.getUserKey(couponId, userId))
        ).isFalse();

        assertThat(couponIssueRepository.countByCoupon_Id(couponId)).isZero();
    }
}
