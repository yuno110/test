package com.example.test.cache;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * cache 패키지 학습 테스트의 공통 준비물. 테스트 클래스는 이걸 상속만 하면 된다.
 *
 * - @SpringBootTest : 앱 전체를 띄운다. 캐시는 JPA, 트랜잭션과 엮여 있어서 Redis 만 띄워서는 안 된다.
 *   H2 에는 MemberDataInitializer 가 회원 3명(1 yoon, 2 kim, 3 lee)을 넣어둔다.
 * - WSL 의 Redis 가 켜져 있어야 한다. (redis-cli ping → PONG)
 *
 * 테스트가 "시작할 때" 캐시를 전부 비우고, 끝날 때는 남겨둔다.
 * 그래서 테스트 하나만 돌린 직후 redis-cli 로 결과를 직접 볼 수 있다.
 *   > KEYS v1::*
 */
@SpringBootTest
abstract class CacheTestSupport {

	@Autowired
	protected StringRedisTemplate redis;

	/** 캐시 키(v1::...)와 분산락 실습 키(stampedeLocked::...)를 지운다. (KEYS 는 운영에선 금지, 테스트라서 쓴다) */
	@BeforeEach
	void 캐시_비우기() {
		redis.delete(redis.keys("v1::*"));
		redis.delete(redis.keys("stampedeLocked::*"));
	}

	/** 스레드 n 개를 "같은 순간에" 출발시켜 task 를 실행하고, 전부 끝날 때까지 기다린다. */
	protected static void 동시에_실행(int n, Runnable task) {
		CountDownLatch 출발신호 = new CountDownLatch(1);

		try (ExecutorService pool = Executors.newFixedThreadPool(n)) {
			for (int i = 0; i < n; i++) {
				pool.submit(() -> {
					출발신호.await();     // 다 같이 출발신호를 기다린다
					task.run();
					return null;
				});
			}
			출발신호.countDown();          // 출발!
		}   // 블록을 나가면서 전부 끝날 때까지 기다린다
	}

	/** action 이 몇 ms 걸렸는지 잰다. */
	protected static long 걸린_ms(Runnable action) {
		long start = System.nanoTime();
		action.run();
		return (System.nanoTime() - start) / 1_000_000;
	}
}
