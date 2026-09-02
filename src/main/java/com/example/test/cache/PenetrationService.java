package com.example.test.cache;

import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import com.example.test.member.MemberDto;
import com.example.test.member.MemberRepository;

import lombok.RequiredArgsConstructor;

/**
 * ③ Cache Penetration (캐시 관통) 실습.
 *
 * 상황: 존재하지 않는 id 를 계속 조회하면 캐시에는 영원히 아무것도 안 쌓인다.
 *      (예외를 던지거나 null 을 반환하면 @Cacheable 은 아무것도 저장하지 않는다)
 *      그래서 매 요청이 100% 미스 → DB 직행. 무작위 id 로 때리는 공격 벡터가 되기도 한다.
 *
 * 대응: null 자체를 "없음"이라는 값으로 짧게 캐싱한다.
 *      TTL 을 짧게 주는 이유는, 그 데이터가 곧 생길 수도 있기 때문이다.
 *      (더 큰 규모에서는 Bloom filter 로 "확실히 없는 키"를 앞단에서 걸러낸다)
 */
@Service
@RequiredArgsConstructor
public class PenetrationService {

	private final MemberRepository repository;

	private final AtomicInteger dbHits = new AtomicInteger();

	public int dbHits() {
		return dbHits.get();
	}

	public void resetCounter() {
		dbHits.set(0);
	}

	/**
	 * 대책 없음. 없는 id 면 예외를 던지고, 예외는 캐싱되지 않는다.
	 * → 같은 id 로 100번 조회하면 DB 도 100번 맞는다.
	 */
	@Cacheable(cacheNames = CacheNames.PENETRATION_STRICT, key = "#id")
	public MemberDto strict(Long id) {
		dbHits.incrementAndGet();
		return repository.findById(id)
				.map(MemberDto::from)
				.orElseThrow(() -> new NoSuchElementException("member not found: " + id));
	}

	/**
	 * null 을 반환하고, 이 캐시는 null 저장이 허용돼 있다(CacheConfig 참고).
	 * → 두 번째 조회부터는 DB 를 치지 않는다. Redis 에는 NullValue 마커가 저장된다.
	 */
	@Cacheable(cacheNames = CacheNames.PENETRATION_NULLABLE, key = "#id")
	public MemberDto nullable(Long id) {
		dbHits.incrementAndGet();
		return repository.findById(id).map(MemberDto::from).orElse(null);
	}
}
