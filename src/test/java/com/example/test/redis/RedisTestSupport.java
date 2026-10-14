package com.example.test.redis;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.redis.test.autoconfigure.DataRedisTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import com.example.test.config.RedisConfig;

/**
 * redis 패키지 학습 테스트의 공통 준비물. 테스트 클래스는 이걸 상속만 하면 된다.
 *
 * - @DataRedisTest : Redis 관련 빈만 띄운다. 웹, JPA 는 안 떠서 빠르다.
 * - @Import(RedisConfig.class) : 우리가 만든 jsonRedisTemplate 도 같이 띄운다.
 * - WSL 의 Redis 가 켜져 있어야 한다. (redis-cli ping → PONG)
 *
 * 키는 테스트 클래스마다 하나씩 쓴다. ListTest 라면 test:ListTest.
 * 키가 더 필요하면 key + ":yoon" 처럼 뒤에 붙여 쓴다.
 * 테스트가 "시작할 때" 지우고, 끝날 때는 남겨둔다.
 * 그래서 테스트 하나만 돌린 직후 redis-cli 로 결과를 직접 볼 수 있다.
 *   > LRANGE test:ListTest 0 -1
 */
@DataRedisTest
@Import(RedisConfig.class)
abstract class RedisTestSupport {

	@Autowired
	protected StringRedisTemplate redis;

	protected final String key = "test:" + getClass().getSimpleName();

	/** test:ListTest, test:ListTest:yoon ... 이 클래스가 쓰는 키를 전부 지운다. (KEYS 는 운영에선 금지, 테스트라서 쓴다) */
	@BeforeEach
	void 키_비우기() {
		redis.delete(redis.keys(key + "*"));
	}
}
