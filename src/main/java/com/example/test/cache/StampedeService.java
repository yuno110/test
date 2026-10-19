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

	/** locked() 가 직접 저장하는 캐시의 TTL */
	private static final Duration CACHE_TTL = Duration.ofSeconds(30);
	/** locked() 의 락 TTL. 락을 잡은 쪽이 죽어도 이 시간이 지나면 저절로 풀린다 */
	private static final Duration LOCK_TTL = Duration.ofSeconds(5);

	private final StringRedisTemplate redis;
	/** Redis 에 "지금 로딩 중" 표시를 거는 분산 락. 서버가 여러 대여도 다 같이 본다 (RedisLock 참고) */
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
	 * CacheConfig.cacheWriter 의 락을 켜면(--cache.locking-writer=true) 1회로 줄어든다.
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
	 *
	 * 빵집으로 치면: 진열대(캐시)가 비었을 때, 주방 문에 "굽는 중" 팻말(락)을 먼저 건 1명만 굽고
	 * 나머지는 진열대를 보며 기다린다.
	 *
	 *   ① 캐시 확인     ─ 있다 → 반환
	 *   ② 락 시도       ─ 실패 → ③ awaitCache (캐시가 채워질 때까지 기다림)
	 *   ④ 캐시 다시 확인 ─ 있다 → 반환
	 *   ⑤ 로드 → ⑥ 캐시 저장 → ⑦ 락 해제 (finally)
	 *
	 * 키: stampedeLocked::{id} (캐시), lock::stampedeLocked::{id} (락)
	 * @Cacheable 을 거치지 않으므로 CacheConfig 의 규칙(v1:: 접두어, 지터, JSON)은 적용되지 않는다.
	 */
	public String locked(String id) {
		String cacheKey = "stampedeLocked::" + id;

		// ① 캐시 확인. 평소에는 거의 다 여기서 끝난다 (GET stampedeLocked::{id})
		String cached = redis.opsForValue().get(cacheKey);
		if (cached != null) {
			return cached;
		}

		// ② 락 시도. 동시에 와도 1명만 성공한다. 실패하면 기다리지 않고 바로 null
		//    (SET lock::stampedeLocked::{id} <토큰> NX PX 5000)
		String lockKey = "lock::" + cacheKey;
		String token = lock.tryLock(lockKey, LOCK_TTL);

		if (token == null) {
			// ③ 남이 로딩 중이다. 나까지 DB 를 칠 이유가 없으니 캐시가 채워질 때까지 기다린다.
			return awaitCache(cacheKey, id);
		}

		try {
			// ④ 한 번 더 확인. ①과 ② 사이에 앞사람이 이미 채우고 락까지 풀었을 수 있다 (double-checked)
			String again = redis.opsForValue().get(cacheKey);
			if (again != null) {
				return again;
			}
			// ⑤ 로드 (DB)
			String value = slowLoad(id);
			// ⑥ 캐시 저장 (SET stampedeLocked::{id} <값> EX 30)
			redis.opsForValue().set(cacheKey, value, CACHE_TTL);
			return value;
		}
		finally {
			// ⑦ 락 해제. 로드하다 에러가 나도, ④에서 바로 반환해도 반드시 푼다.
			//    내 토큰일 때만 지운다 (RedisLock.unlock)
			lock.unlock(lockKey, token);
		}
	}

	/**
	 * ③ 락을 못 잡은 쪽이 캐시가 채워지길 기다린다.
	 * 20ms 마다 GET 해서 값이 생기면 그걸 쓰고, 5초(LOCK_TTL)가 지나도 안 채워지면 직접 로드한다(무한 대기 금지).
	 *
	 * 약점: 락이 아니라 캐시만 본다. 락을 잡은 쪽이 5초 넘게 걸리거나 로드에 실패하면,
	 * 기다리던 쪽이 전부 5초에 동시에 slowLoad 로 가서 스탬피드가 다시 생긴다.
	 */
	private String awaitCache(String cacheKey, String id) {
		// 락이 5초면 저절로 풀리니, 그보다 오래 기다릴 이유가 없다
		long deadline = System.currentTimeMillis() + LOCK_TTL.toMillis();
		while (System.currentTimeMillis() < deadline) {
			String value = redis.opsForValue().get(cacheKey);
			if (value != null) {
				return value;
			}
			try {
				Thread.sleep(20);   // 쉬지 않고 GET 하면 Redis 를 괴롭힌다
			}
			catch (InterruptedException ex) {
				// Thread.sleep 을 쓰면 꼭 붙여야 하는 형식. 누가 이 스레드를 멈추라고 하면 기다리기를 그만둔다
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
