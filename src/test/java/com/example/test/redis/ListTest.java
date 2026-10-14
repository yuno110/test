package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ListOperations;

/**
 * List : 값을 순서대로 늘어놓은 줄. 양쪽 끝이 다 열려 있다.
 *
 *        left(앞)            right(뒤)
 *           ↓                    ↓
 *         [ a,    b,    c ]
 *
 * 쓰임: 작업 대기열(큐), 최근 본 목록
 * 키: test:ListTest
 */
class ListTest extends RedisTestSupport {

	private ListOperations<String, String> list;

	@BeforeEach
	void 준비() {
		list = redis.opsForList();
	}

	@Test
	void rightPush는_뒤에_붙인다() {
		list.rightPushAll(key, "a", "b", "c");                 // RPUSH key a b c

		assertThat(list.range(key, 0, -1))                     // LRANGE key 0 -1
				.containsExactly("a", "b", "c");
	}

	@Test
	void leftPush는_앞에_끼운다() {
		list.leftPushAll(key, "a", "b", "c");                  // LPUSH key a b c

		// a 를 앞에, 그 앞에 b, 그 앞에 c → 넣은 순서가 뒤집힌다
		assertThat(list.range(key, 0, -1))
				.containsExactly("c", "b", "a");
	}

	@Test
	void range의_마이너스_번호는_뒤에서부터_센다() {
		list.rightPushAll(key, "a", "b", "c", "d");

		assertThat(list.range(key, 0, -1)).containsExactly("a", "b", "c", "d");   // 처음부터 끝까지
		assertThat(list.range(key, 0, 1)).containsExactly("a", "b");             // 앞에서 2개
		assertThat(list.range(key, -2, -1)).containsExactly("c", "d");           // 뒤에서 2개
	}

	@Test
	void 큐_뒤로_넣고_앞에서_꺼내면_넣은_순서대로_나온다() {
		list.rightPushAll(key, "order1", "order2", "order3");   // RPUSH

		assertThat(list.leftPop(key)).isEqualTo("order1");      // LPOP key
		assertThat(list.leftPop(key)).isEqualTo("order2");

		// 꺼낸 건 리스트에서 빠진다
		assertThat(list.range(key, 0, -1)).containsExactly("order3");
	}

	@Test
	void 최근_본_목록_앞에_넣고_3개만_남긴다() {
		for (String product : List.of("p1", "p2", "p3", "p4", "p5")) {
			list.leftPush(key, product);                        // LPUSH key p1 : 방금 본 걸 맨 앞에
			list.trim(key, 0, 2);                               // LTRIM key 0 2 : 앞에서 3개만 남기고 버림
		}

		assertThat(list.range(key, 0, -1))
				.containsExactly("p5", "p4", "p3");
	}
}
