package com.example.test.cache;

import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * ④ 트랜잭션과 캐시의 불일치 실습.
 *
 * 상황: 트랜잭션 안에서 캐시를 지웠는데, 그 뒤에 트랜잭션이 롤백되면?
 *        - 가벼운 피해: DB 는 그대로인데 캐시만 날아가서 쓸데없는 미스가 생긴다.
 *        - 진짜 피해: evict 직후 ~ 커밋 전 사이에 다른 스레드가 조회하면
 *                   "아직 커밋 안 된 옛 값"을 다시 캐싱한다. 커밋 후에도 캐시에는 옛 값이
 *                   TTL 내내 남는다. 이게 흔히 말하는 stale cache 사고다.
 *
 * 대응: CacheConfig 의 transactionAware() — 트랜잭션 안에서 부른 캐시 연산을
 *      "커밋되면 실행해라"로 예약만 해 둔다. 롤백되면 예약이 버려져서 캐시는 그대로다.
 *
 * 왜 여기서는 @CacheEvict 대신 CacheManager 로 직접 지우나:
 *   @CacheEvict 는 기본적으로 "메서드가 예외 없이 끝났을 때만" 지운다(beforeInvocation = false).
 *   그래서 예외를 던지는 메서드에 붙이면 transactionAware 와 상관없이 애초에 안 지운다.
 *   (예전 코드가 그랬다. transactionAware() 를 꺼도 똑같이 "캐시 살아남음"이 나왔다)
 *   "지운 다음에 실패한다"는 순서를 확실히 만들려고 메서드 본문에서 직접 지운다.
 *   실무에서는 큰 @Transactional 메서드 안에서 다른 서비스의 @CacheEvict 메서드를 부른 뒤
 *   뒤쪽 단계에서 실패하는 경우가 이와 같다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TransactionalCacheService {

	private final CacheManager cacheManager;

	@Cacheable(cacheNames = CacheNames.TX_DEMO, key = "#id")
	public String load(Long id) {
		log.info("TX_DEMO 로더 실행 id={}", id);
		return "value-of-" + id;
	}

	/** 캐시를 지운 다음 실패해서 롤백된다. transactionAware() 라면 캐시는 살아남아야 한다. */
	@Transactional
	public void evictThenRollback(Long id) {
		// transactionAware() 라서 지금 바로 지우지 않고 "커밋되면 지워라"로 예약만 된다
		txDemoCache().evict(id);
		log.info("evict 예약 후 예외를 던진다 id={}", id);
		throw new IllegalStateException("일부러 낸 예외 (롤백 유도)");
	}

	/** 비교용 - 정상 커밋되면 예약해 둔 evict 가 그때 실행된다. */
	@Transactional
	public void evictAndCommit(Long id) {
		txDemoCache().evict(id);
		log.info("정상 커밋 경로 id={}", id);
	}

	/** transactionAware() 가 켜져 있으면 TransactionAwareCacheDecorator 로 감싸진 캐시가 나온다 */
	private Cache txDemoCache() {
		return cacheManager.getCache(CacheNames.TX_DEMO);
	}
}
