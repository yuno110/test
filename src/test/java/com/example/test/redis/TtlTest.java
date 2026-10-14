package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * TTL : 키에 붙이는 유통기한. 시간이 지나면 Redis 가 알아서 지운다.
 * String 뿐 아니라 List, Hash 등 모든 키에 붙일 수 있다.
 *
 * 쓰임: 캐시 만료, 인증번호(3분), 로그인 세션
 * 키: test:TtlTest
 */
class TtlTest extends RedisTestSupport {

	@Test
	void TTL을_주면_시간이_지나_저절로_사라진다() throws InterruptedException {
		redis.opsForValue().set(key, "1234", Duration.ofSeconds(1));   // SET key 1234 EX 1

		assertThat(redis.hasKey(key)).isTrue();
		Thread.sleep(1_500);
		assertThat(redis.hasKey(key)).isFalse();
	}

	@Test
	void getExpire는_남은_초를_알려준다() {
		redis.opsForValue().set(key, "hello");                         // SET key hello (TTL 없음)
		assertThat(redis.getExpire(key)).isEqualTo(-1L);               // TTL key → -1 : 만료 없음

		redis.expire(key, Duration.ofSeconds(60));                     // EXPIRE key 60 : 나중에 TTL 붙이기
		assertThat(redis.getExpire(key)).isBetween(59L, 60L);          // TTL key → 60

		redis.delete(key);
		assertThat(redis.getExpire(key)).isEqualTo(-2L);               // TTL key → -2 : 키가 없음
	}

	@Test
	void TTL_있는_키를_TTL_없이_다시_set하면_만료가_풀린다() {
		redis.opsForValue().set(key, "old", Duration.ofSeconds(60));   // SET key old EX 60
		redis.opsForValue().set(key, "new");                           // SET key new  ← EX 없이

		// 영원히 안 지워지는 키가 됐다. 캐시를 갱신하는 코드에서 흔히 하는 실수
		assertThat(redis.getExpire(key)).isEqualTo(-1L);
	}
}
