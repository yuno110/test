package com.example.test.cache;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ② 스탬피드 : 캐시가 비어 있는 순간 같은 요청 20개가 동시에 들어오면, 20개 모두 DB 로 간다.
 *
 * 실제 DB 대신 StampedeService 의 slowLoad(0.4초)를 쓰고, 몇 번 불렸는지(loaderCalls) 센다.
 * Spring 의 락(락킹 캐시 라이터)을 켠 경우는 StampedeWithLockingWriterTest 에 따로 있다.
 */
class StampedeTest extends CacheTestSupport {

	@Autowired
	private StampedeService stampede;

	@BeforeEach
	void 준비() {
		stampede.resetCounter();
	}

	@Test
	void 대책이_없으면_동시요청_수만큼_DB를_친다() {
		동시에_실행(20, () -> stampede.naive("hot"));

		assertThat(stampede.loaderCalls()).isEqualTo(20);
	}

	@Test
	void sync_true만_붙이면_효과가_없다() {
		동시에_실행(20, () -> stampede.synced("hot"));

		// 기본 캐시 라이터에는 락이 없어서 @Cacheable(sync = true) 가 아무 일도 안 한다
		assertThat(stampede.loaderCalls()).isEqualTo(20);
	}

	@Test
	void 분산락을_쓰면_한_번만_DB를_친다() {
		동시에_실행(20, () -> stampede.locked("hot"));

		// 락(RedisLock)을 잡은 1명만 로드하고, 나머지는 캐시가 채워지길 기다렸다가 그 값을 쓴다
		assertThat(stampede.loaderCalls()).isEqualTo(1);
	}
}
