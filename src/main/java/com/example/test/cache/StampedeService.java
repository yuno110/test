package com.example.test.cache;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ② Cache Stampede (Thundering Herd) 실습.
 *
 * 상황: 인기 키의 TTL 이 만료되는 그 순간, 동시에 들어온 N 개의 요청이 전부 미스가 되어
 *      한꺼번에 DB 로 몰린다. 캐시 덕에 버티던 DB 가 바로 그 순간 무너진다.
 *
 * 세 가지 방식을 같은 조건에서 비교할 수 있게 "느린 로더" 호출 횟수를 센다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StampedeService {

	private static final Duration CACHE_TTL = Duration.ofSeconds(30);
	private static final Duration LOCK_TTL = Duration.ofSeconds(5);

	private final StringRedisTemplate redis;
	private final RedisLock lock;

	/** 느린 원본(DB 라고 치자)이 실제로 몇 번 불렸는지 */
	private final AtomicInteger loaderCalls = new AtomicInteger();

	public int loaderCalls() {
		return loaderCalls.get();
	}

	public void resetCounter() {
		loaderCalls.set(0);
	}

	/** (1) 대책 없음 - 동시 요청 수만큼 로더가 돈다 */
	@Cacheable(cacheNames = CacheNames.STAMPEDE_NAIVE, key = "#id")
	public String naive(String id) {
		return slowLoad(id);
	}

	/**
	 * (2) sync = true - "이미 누가 로딩 중이면 기다렸다가 그 결과를 쓴다".
	 *
	 * 함정: 이건 캐시 라이터가 locking 일 때만 동작한다.
	 * Boot 의 기본 캐시매니저는 non-locking 이라서 sync = true 를 붙여도 아무 일도 안 일어난다.
	 * (RedisCache.get(key, loader) -> DefaultRedisCacheWriter.get(...) 안에서
	 *  isLockingCacheWriter() 가 false 면 그냥 GET 하고 없으면 로드한다)
	 *
	 * CacheConfig.lockingCacheManager 를 켜면(--cache.locking-writer=true) 1회로 줄어든다.
	 * 그 락은 Redis 에 저장되므로 인스턴스가 여러 대여도 유효하지만,
	 * "캐시 이름 단위" 락이라 그 캐시 전체가 잠기고, 대기자는 폴링으로 기다려서 지연이 길다.
	 */
	@Cacheable(cacheNames = CacheNames.STAMPEDE_SYNC, key = "#id", sync = true)
	public String synced(String id) {
		return slowLoad(id);
	}

	/**
	 * (3) 분산 락 - 여러 인스턴스를 띄워도 전체에서 딱 한 번만 로드된다.
	 * @Cacheable 을 쓰지 않고 캐시 조회/저장을 직접 한다(수동 Cache-Aside).
	 */
	public String locked(String id) {
		String cacheKey = "stampedeLocked::" + id;

		String cached = redis.opsForValue().get(cacheKey);
		if (cached != null) {
			return cached;
		}

		String lockKey = "lock::" + cacheKey;
		String token = lock.tryLock(lockKey, LOCK_TTL);

		if (token == null) {
			// 남이 로딩 중이다. 나까지 DB 를 칠 이유가 없으니 채워질 때까지 잠깐 기다린다.
			return awaitCache(cacheKey, id);
		}

		try {
			// 락을 잡는 사이에 앞사람이 이미 채웠을 수 있다 (double-checked)
			String again = redis.opsForValue().get(cacheKey);
			if (again != null) {
				return again;
			}
			String value = slowLoad(id);
			redis.opsForValue().set(cacheKey, value, CACHE_TTL);
			return value;
		}
		finally {
			lock.unlock(lockKey, token);
		}
	}

	/** 락을 못 잡은 쪽이 캐시가 채워지길 기다린다. 끝내 안 채워지면 스스로 로드한다(무한 대기 금지). */
	private String awaitCache(String cacheKey, String id) {
		long deadline = System.currentTimeMillis() + LOCK_TTL.toMillis();
		while (System.currentTimeMillis() < deadline) {
			String value = redis.opsForValue().get(cacheKey);
			if (value != null) {
				return value;
			}
			try {
				Thread.sleep(20);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		log.warn("락 보유자가 제때 못 채웠다. 폴백으로 직접 로드한다: {}", cacheKey);
		return slowLoad(id);
	}

	/** 400ms 걸리는 무거운 조회라고 가정한다. */
	private String slowLoad(String id) {
		loaderCalls.incrementAndGet();
		try {
			Thread.sleep(400);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
		return "loaded:" + id;
	}
}
