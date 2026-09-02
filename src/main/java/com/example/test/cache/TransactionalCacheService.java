package com.example.test.cache;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * ④ 트랜잭션과 캐시의 불일치 실습.
 *
 * 상황: @Transactional 안에서 @CacheEvict 가 돌면, 기본 설정에서는 evict 가 즉시 실행된다.
 *      그런데 그 뒤에 트랜잭션이 롤백되면?
 *        - 가벼운 피해: DB 는 그대로인데 캐시만 날아가서 쓸데없는 미스가 생긴다.
 *        - 진짜 피해: evict 직후 ~ 커밋 전 사이에 다른 스레드가 조회하면
 *                   "아직 커밋 안 된 옛 값"을 다시 캐싱한다. 커밋 후에도 캐시에는 옛 값이
 *                   TTL 내내 남는다. 이게 흔히 말하는 stale cache 사고다.
 *
 * 대응: CacheConfig 의 transactionAware() — 캐시 연산을 커밋 이후로 미룬다.
 *      롤백되면 캐시 연산 자체가 실행되지 않는다.
 */
@Slf4j
@Service
public class TransactionalCacheService {

	@Cacheable(cacheNames = CacheNames.TX_DEMO, key = "#id")
	public String load(Long id) {
		log.info("TX_DEMO 로더 실행 id={}", id);
		return "value-of-" + id;
	}

	/** evict 를 걸어놓고 일부러 롤백시킨다. transactionAware() 라면 캐시는 살아남아야 한다. */
	@CacheEvict(cacheNames = CacheNames.TX_DEMO, key = "#id")
	@Transactional
	public void evictThenRollback(Long id) {
		log.info("evict 예약 후 예외를 던진다 id={}", id);
		throw new IllegalStateException("일부러 낸 예외 (롤백 유도)");
	}

	/** 비교용 - 정상 커밋되면 evict 가 적용된다. */
	@CacheEvict(cacheNames = CacheNames.TX_DEMO, key = "#id")
	@Transactional
	public void evictAndCommit(Long id) {
		log.info("정상 커밋 경로 id={}", id);
	}
}
