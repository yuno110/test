package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.SetOperations;

/**
 * Set : 중복 없는 주머니. 순서도 없다.
 *
 *   test:SetTest  →  { redis, java, spring }
 *
 * 쓰임: 태그, 좋아요 누른 사람 목록, 오늘 방문한 사용자
 * 키: test:SetTest  (교집합 예제는 test:SetTest:yoon, test:SetTest:kim)
 */
class SetTest extends RedisTestSupport {

	private SetOperations<String, String> set;

	@BeforeEach
	void 준비() {
		set = redis.opsForSet();
	}

	@Test
	void 같은_값을_여러_번_넣어도_하나만_남는다() {
		Long added = set.add(key, "redis", "java", "spring", "redis");   // SADD key redis java spring redis

		assertThat(added).isEqualTo(3);                                  // 실제로 새로 들어간 개수
		assertThat(set.members(key))                                     // SMEMBERS key
				.containsExactlyInAnyOrder("redis", "java", "spring");   // 순서는 보장 안 된다
	}

	@Test
	void isMember로_들어있는지_바로_확인한다() {
		set.add(key, "user1", "user2");                                  // 이 글에 좋아요 누른 사람들

		assertThat(set.isMember(key, "user1")).isTrue();                 // SISMEMBER key user1 → 1
		assertThat(set.isMember(key, "user3")).isFalse();                // SISMEMBER key user3 → 0
	}

	@Test
	void intersect로_두_Set에_공통으로_있는_것을_찾는다() {
		String yoon = key + ":yoon";
		String kim = key + ":kim";
		set.add(yoon, "redis", "java", "spring");                        // yoon 의 관심사
		set.add(kim, "java", "spring", "kotlin");                        // kim 의 관심사

		assertThat(set.intersect(yoon, kim))                             // SINTER yoon kim
				.containsExactlyInAnyOrder("java", "spring");
	}
}
