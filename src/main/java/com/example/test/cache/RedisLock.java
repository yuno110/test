package com.example.test.cache;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * ② 스탬피드 대응 - 분산 락.
 *
 * @Cacheable(sync = true) 는 "같은 JVM 안에서만" 한 스레드로 좁혀준다.
 * 서버를 4대 띄우면 4번 로드된다. 인스턴스가 여러 대인 실무에서는 Redis 자체를 락으로 쓴다.
 *
 * 핵심 두 가지:
 *  1) 획득은 SET key token NX PX ttl 한 방으로. (setIfAbsent 가 이 명령이다)
 *     "존재 확인 후 SET" 으로 나누면 그 사이에 남이 끼어든다.
 *  2) 해제는 반드시 "내 토큰일 때만" 지운다. 그리고 그 확인과 삭제가 원자적이어야 한다.
 *     내 작업이 TTL 보다 오래 걸려 락이 이미 남에게 넘어갔는데 내가 DEL 하면
 *     남의 락을 풀어버리는 참사가 난다. 그래서 Lua 스크립트를 쓴다.
 *
 * 실무에서는 보통 Redisson 을 쓴다(락 연장 watchdog, 재진입, 페어락 등을 제공).
 * 여기서는 원리를 보기 위해 최소 구현을 직접 만든다.
 */
@Component
@RequiredArgsConstructor
public class RedisLock {

	/** GET 해서 내 토큰이면 DEL. 아니면 아무것도 안 함. 이 전체가 원자적으로 실행된다. */
	private static final RedisScript<Long> UNLOCK = new DefaultRedisScript<>("""
			if redis.call('get', KEYS[1]) == ARGV[1] then
			    return redis.call('del', KEYS[1])
			end
			return 0
			""", Long.class);

	private final StringRedisTemplate redis;

	/**
	 * @return 획득했으면 해제에 쓸 토큰, 실패하면 null
	 */
	public String tryLock(String lockKey, Duration ttl) {
		String token = UUID.randomUUID().toString();
		boolean acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(lockKey, token, ttl));
		return acquired ? token : null;
	}

	/** 내가 잡은 락일 때만 푼다. */
	public boolean unlock(String lockKey, String token) {
		return Long.valueOf(1L).equals(redis.execute(UNLOCK, List.of(lockKey), token));
	}
}
