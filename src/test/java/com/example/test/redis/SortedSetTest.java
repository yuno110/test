package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.ZSetOperations;

/**
 * Sorted Set : 점수가 붙은 Set. 중복이 없고, Redis 가 점수 순으로 알아서 정렬해 둔다.
 *
 *   test:SortedSetTest  →  { alice:100, carol:175, bob:250 }
 *
 * 쓰임: 랭킹, 인기 검색어
 * 키: test:SortedSetTest
 * 주의: redis-cli 는 ZADD key 100 alice (점수 먼저), 자바는 add(key, "alice", 100) (멤버 먼저)
 */
class SortedSetTest extends RedisTestSupport {

	private ZSetOperations<String, String> zset;

	@BeforeEach
	void 준비() {
		zset = redis.opsForZSet();
	}

	@Test
	void 넣은_순서와_상관없이_점수_순으로_정렬된다() {
		zset.add(key, "alice", 100);                             // ZADD key 100 alice
		zset.add(key, "bob", 250);
		zset.add(key, "carol", 175);

		assertThat(zset.range(key, 0, -1))                       // ZRANGE key 0 -1 : 낮은 점수부터
				.containsExactly("alice", "carol", "bob");
		assertThat(zset.reverseRange(key, 0, -1))                // ZREVRANGE key 0 -1 : 높은 점수부터
				.containsExactly("bob", "carol", "alice");
	}

	@Test
	void 같은_멤버를_다시_넣으면_점수만_바뀐다() {
		zset.add(key, "alice", 100);
		zset.add(key, "bob", 250);
		zset.add(key, "alice", 300);                             // alice 가 하나 더 생기지 않고 점수만 300

		assertThat(zset.reverseRange(key, 0, -1)).containsExactly("alice", "bob");
	}

	@Test
	void incrementScore로_점수를_더한다() {
		zset.add(key, "alice", 100);

		zset.incrementScore(key, "alice", 50);                   // ZINCRBY key 50 alice

		assertThat(zset.score(key, "alice")).isEqualTo(150.0);   // ZSCORE key alice
	}

	@Test
	void reverseRank로_등수를_본다_1등이_0이다() {
		zset.add(key, "alice", 100);
		zset.add(key, "bob", 250);
		zset.add(key, "carol", 175);

		assertThat(zset.reverseRank(key, "bob")).isEqualTo(0);   // ZREVRANK key bob → 0 (1등)
		assertThat(zset.reverseRank(key, "alice")).isEqualTo(2); // 3등
	}
}
