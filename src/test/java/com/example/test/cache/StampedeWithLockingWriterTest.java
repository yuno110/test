package com.example.test.cache;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

/**
 * ② 스탬피드 - Spring 이 제공하는 락(락킹 캐시 라이터)을 켠 경우.
 *
 * 이 클래스만 cache.locking-writer=true 로 앱을 따로 띄운다. (config/CacheConfig.cacheWriter 참고)
 * 그래서 다른 cache 테스트보다 처음 시작이 몇 초 더 걸린다.
 *
 * 락 키: stampedeSync~lock  (캐시 이름 + "~lock". 어떤 키를 로딩 중인지는 이름에 없다)
 */
@TestPropertySource(properties = "cache.locking-writer=true")
class StampedeWithLockingWriterTest extends CacheTestSupport {

	/**
	 * 락 키는 우리가 만드는 게 아니라 Spring 이 알아서 만든다. 규칙: "캐시이름~lock"
	 * (Spring Data Redis 의 DefaultRedisCacheWriter.createCacheLockKey → name + "~lock")
	 * enableLocking() 을 켰을 때만 생기고, 로딩이 끝나면 Spring 이 지운다.
	 *
	 * 이 상수는 Redis 에 아무것도 넣지 않는다. 테스트가 "Spring 이 락을 걸었나?" 를 확인하려고
	 * 같은 이름을 적어둔 것뿐이다. (redis-cli: EXISTS stampedeSync~lock)
	 */
	private static final String 락_키 = "stampedeSync~lock";

	@Autowired
	private StampedeService stampede;

	@BeforeEach
	void 준비() {
		stampede.resetCounter();
		// 락 키에는 만료 시간이 없다. 앞 테스트가 로딩 중에 죽으면 락 키가 영원히 남아서
		// 이 캐시의 모든 조회가 멈춘다. 그래서 시작할 때 직접 지운다
		redis.delete(락_키);
	}

	@Test
	void 락킹_라이터를_켜면_sync_true가_동작해서_한_번만_DB를_친다() {
		동시에_실행(20, () -> stampede.synced("hot"));

		assertThat(stampede.loaderCalls()).isEqualTo(1);
	}

	/**
	 * 단점: 락이 키 하나가 아니라 stampedeSync 캐시 "전체"에 걸린다.
	 *
	 *   0.0초  A: synced("hot")   캐시에 없음 → stampedeSync~lock 잡음 → 0.4초 로딩
	 *   0.1초  B: synced("cold")  캐시에 있는데도 락 때문에 기다림
	 *   0.4초  A: 저장하고 락 풀기 → B 가 그제야 cold 를 받는다
	 *
	 * 키 단위 분산락(StampedeService.locked)은 락 키가 lock::stampedeLocked::hot 이라서 cold 는 바로 받는다.
	 */
	@Test
	void 락이_캐시_전체에_걸려서_캐시에_있는_다른_키도_기다린다() throws Exception {
		stampede.synced("cold");                                                    // cold 는 미리 캐싱

		CompletableFuture<String> hot_로딩 = CompletableFuture.supplyAsync(() -> stampede.synced("hot"));
		// 위 줄은 hot 로딩을 다른 스레드에 맡기고 바로 넘어온다. 아직 락이 안 걸렸을 수 있으니,
		// hot 스레드 안에서 Spring 이 stampedeSync~lock 을 만들 때까지 기다린다
		while (!redis.hasKey(락_키)) {
			Thread.sleep(10);
		}

		// getExpire = redis-cli 의 TTL. "몇 초 뒤에 사라질 예정인가?" 를 묻는다
		//   양수 → 그 초 뒤에 사라짐 / -1 → 키는 있는데 사라질 예정이 없음 / -2 → 키가 없음
		// Spring 의 락 키는 -1 이다. 서버가 로딩 중에 죽으면 아무도 안 지워서 영원히 남는다
		// (우리가 만든 RedisLock 은 5초 타이머가 있어서 5 근처가 나온다)
		assertThat(redis.getExpire(락_키)).isEqualTo(-1L);

		// 맨 위와 같은 호출이지만 이번엔 cold 가 이미 캐시에 있다 → 로드 없이 꺼내기만 한다 (평소 몇 ms)
		// 그런데 hot 이 stampedeSync~lock 을 잡고 있어서, 꺼내기 전에 락이 풀릴 때까지 기다린다
		long cold_조회 = 걸린_ms(() -> stampede.synced("cold"));
		// hot 스레드가 끝날 때까지 기다렸다가 결과("loaded:hot")를 받는다. 결과는 안 쓰고, 기다리는 게 목적이다
		//  - 아래 loaderCalls 를 hot 이 끝난 뒤에 세려고
		//  - hot 스레드에서 에러가 났으면 여기서 터뜨려 테스트를 실패시키려고 (안 부르면 조용히 묻힌다)
		// cold 가 이미 락이 풀릴 때까지(= hot 이 끝날 때까지) 기다렸으니, 실제로는 거의 바로 끝난다
		hot_로딩.get();

		assertThat(cold_조회).isGreaterThan(200);                                   // hot 로딩이 끝날 때까지 기다렸다 (보통 300ms 대)
		assertThat(stampede.loaderCalls()).isEqualTo(2);                            // cold 는 다시 로드되지 않았다 (cold 1번 + hot 1번)
	}
}
