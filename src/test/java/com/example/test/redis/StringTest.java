package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;

/**
 * String : 키 하나에 값 하나. 가장 기본.
 *
 *   test:StringTest  →  "hello"
 *
 * 쓰임: 캐시, 인증 토큰, 락(cache/RedisLock)
 * 키: test:StringTest
 */
class StringTest extends RedisTestSupport {

	private ValueOperations<String, String> value;

	@BeforeEach
	void 준비() {
		value = redis.opsForValue();
	}

	@Test
	void set하고_get하면_그대로_나온다() {
		value.set(key, "hello");                                 // SET key hello

		assertThat(value.get(key)).isEqualTo("hello");           // GET key
	}

	@Test
	void 없는_키를_get하면_null이_나온다() {
		assertThat(value.get(key)).isNull();                     // GET key → (nil)
	}

	@Test
	void 같은_키에_다시_set하면_덮어쓴다() {
		value.set(key, "first");
		value.set(key, "second");

		assertThat(value.get(key)).isEqualTo("second");
	}

	@Test
	void setIfAbsent는_키가_없을_때만_저장한다() {
		assertThat(value.setIfAbsent(key, "first")).isTrue();    // SET key first NX → 저장됨
		assertThat(value.setIfAbsent(key, "second")).isFalse();  // 이미 있으니 무시

		// "먼저 온 사람만 성공" → 분산 락(cache/RedisLock)이 이걸로 만들어진다
		assertThat(value.get(key)).isEqualTo("first");
	}
}
