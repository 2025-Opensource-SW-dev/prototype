package com.example.api.service;

import com.example.api.repository.CouponRepository;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.data.redis.core.RedisTemplate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import java.util.concurrent.TimeUnit;

@SpringBootTest
public class ApplyServiceTest {

    @Autowired
    private ApplyService applyService;

    @Autowired
    private CouponRepository couponRepository;

    @Autowired
    private RedisTemplate<String, String> redisTemplate;

    @BeforeEach
    public void setUp() {
        // "coupon_count"는 CouponCountRepository에서 사용하는 키 이름으로 가정
        // 실제 사용하는 키 이름으로 변경해야 합니다.
        redisTemplate.delete("coupon_count");
        redisTemplate.delete("applied_user");
    }

    @Test
    public void 한번만응모() {
        applyService.apply(1l);

        long count = couponRepository.count();

        assertThat(count).isEqualTo(1);
    }

    @Test
    public void 여러명응모() throws InterruptedException {
        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long userId = i;
            executorService.submit(() -> {
                try {
                    applyService.apply(userId);
                } finally {
                    latch.countDown();
                }
            });

        }

        latch.await();

        Thread.sleep(10000);

        long count = couponRepository.count();

        assertThat(count).isEqualTo(100);
    }

    @Test
    public void 한명당_한개의쿠폰만_발급() throws InterruptedException {
        int threadCount = 1000;
        ExecutorService executorService = Executors.newFixedThreadPool(32);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            long userId = i;
            executorService.submit(() -> {
                try {
                    applyService.apply(1L);
                } finally {
                    latch.countDown();
                }
            });

        }

        latch.await();

        await().atMost(10, TimeUnit.SECONDS).until(() -> couponRepository.count() == 1L);

        long count = couponRepository.count();

        assertThat(count).isEqualTo(1);

    }
}
