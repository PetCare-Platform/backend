package com.mycom.petcoupon.experiment;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@EnabledIfEnvironmentVariable(named = "RUN_CONCURRENCY_TESTS", matches = "true")
class ExperimentCouponApplicationTests {

    @Test
    void contextLoads() {
    }
}
