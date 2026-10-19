package com.example.test.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ④ 트랜잭션 : 트랜잭션 안에서 캐시를 지웠는데, 그 뒤에 롤백되면?
 *
 * CacheConfig 의 transactionAware() 는 트랜잭션 안의 캐시 작업을 바로 하지 않고
 * "커밋되면 해라"로 예약만 해둔다. 롤백되면 예약도 취소된다.
 *
 * 직접 확인해보기: CacheConfig 에서 .transactionAware() 를 지우고 돌리면 롤백 테스트가 실패한다.
 * 키: v1::txDemo::1
 */
class TransactionTest extends CacheTestSupport {

	@Autowired
	private TransactionalCacheService tx;

	@Test
	void 롤백되면_캐시_삭제도_취소된다() {
		tx.load(1L);                                             // 캐시 채우기

		assertThatThrownBy(() -> tx.evictThenRollback(1L))      // 캐시 삭제 → 예외 → 롤백
				.isInstanceOf(IllegalStateException.class);

		assertThat(redis.hasKey("v1::txDemo::1")).isTrue();     // 캐시가 그대로 남아 있다
	}

	@Test
	void 커밋되면_그때_캐시가_지워진다() {
		tx.load(1L);                                             // 캐시 채우기

		tx.evictAndCommit(1L);                                   // 캐시 삭제 → 커밋

		assertThat(redis.hasKey("v1::txDemo::1")).isFalse();
	}
}
