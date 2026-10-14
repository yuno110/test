package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 카운터 : String 에 숫자를 넣고 INCR 로 올린다.
 * 저장은 글자 "12" 로 되지만, INCR 은 숫자로 보고 더해준다.
 *
 * 쓰임: 조회수, 좋아요 수, 요청 횟수 제한
 * 키: test:CounterTest
 */
class CounterTest extends RedisTestSupport {

	private ValueOperations<String, String> value;

	@BeforeEach
	void 준비() {
		value = redis.opsForValue();
	}

	@Test
	void increment는_1씩_올린다_없는_키는_0에서_시작한다() {
		assertThat(value.increment(key)).isEqualTo(1);           // INCR key → 1
		assertThat(value.increment(key)).isEqualTo(2);           // INCR key → 2
		assertThat(value.increment(key, 10)).isEqualTo(12);      // INCRBY key 10 → 12

		assertThat(value.get(key)).isEqualTo("12");              // 저장은 글자로 되어 있다
	}

	@Test
	void 읽고_더하고_쓰기를_따로_하면_동시_요청에서_숫자가_빠진다() {
		value.set(key, "5");

		// 서버 A 와 B 가 거의 동시에 읽는다
		int readByA = Integer.parseInt(value.get(key));          // 5
		int readByB = Integer.parseInt(value.get(key));          // 5

		// 각자 1 을 더해서 저장한다
		value.set(key, String.valueOf(readByA + 1));             // 6
		value.set(key, String.valueOf(readByB + 1));             // 6

		// 두 번 올렸는데 7 이 아니라 6. 한 번이 사라졌다
		assertThat(value.get(key)).isEqualTo("6");
	}

	@Test
	void increment는_동시에_100번_올려도_정확히_100이다() {
		try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
			for (int i = 0; i < 100; i++) {
				pool.submit(() -> value.increment(key));            // 스레드 10개가 동시에 INCR
			}
		}   // 블록을 나가면서 100번이 다 끝날 때까지 기다린다

		// Redis 는 명령을 하나씩 처리한다. INCR 은 읽기+더하기+저장이 명령 하나라 끼어들 틈이 없다
		assertThat(value.get(key)).isEqualTo("100");
	}
}
