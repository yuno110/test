package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;

/**
 * Hash : 키 하나 안에 작은 Map 하나. 그 Map 안에 "필드 → 값" 항목이 여러 개 들어간다.
 *
 *   test:HashTest  →  { name=yoon, role=student, age=20 }
 *
 * 쓰임: 객체 한 건(회원, 상품)을 필드 단위로 저장. 필드 하나만 읽거나 바꿀 수 있다.
 * 키: test:HashTest
 */
class HashTest extends RedisTestSupport {

	private HashOperations<String, String, String> hash;

	@BeforeEach
	void 준비() {
		hash = redis.opsForHash();
	}

	@Test
	void put하고_get하면_필드_하나를_꺼낸다() {
		hash.put(key, "name", "yoon");                           // HSET key name yoon

		assertThat(hash.get(key, "name")).isEqualTo("yoon");     // HGET key name
	}

	@Test
	void entries는_Map_전체를_꺼낸다() {
		hash.putAll(key, Map.of(                                 // HSET key name yoon role student age 20
				"name", "yoon",
				"role", "student",
				"age", "20"));

		assertThat(hash.entries(key))                            // HGETALL key
				.containsOnly(
						entry("name", "yoon"),
						entry("role", "student"),
						entry("age", "20"));
	}

	@Test
	void 필드_하나만_바꾸거나_지울_수_있다() {
		hash.putAll(key, Map.of("name", "yoon", "role", "student"));

		hash.put(key, "role", "teacher");                        // HSET key role teacher : role 만 바뀐다
		hash.delete(key, "name");                                // HDEL key name

		assertThat(hash.entries(key)).containsOnly(entry("role", "teacher"));
	}
}
