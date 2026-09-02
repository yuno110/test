package com.example.test.cache;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/** 캐시 함정 6가지를 눈으로 확인하는 실습 엔드포인트. */
@RestController
@RequestMapping("/api/cache-pitfalls")
@RequiredArgsConstructor
public class CachePitfallController {

	private final StampedeService stampede;
	private final PenetrationService penetration;
	private final TransactionalCacheService tx;
	private final StringRedisTemplate redis;
	private final CacheManager cacheManager;

	/**
	 * ② 스탬피드: 캐시가 빈 상태에서 동시에 N 개 요청을 때린다.
	 *
	 * curl -X POST "http://localhost:8080/api/cache-pitfalls/stampede?mode=naive&concurrency=20"
	 * curl -X POST "http://localhost:8080/api/cache-pitfalls/stampede?mode=sync&concurrency=20"
	 * curl -X POST "http://localhost:8080/api/cache-pitfalls/stampede?mode=locked&concurrency=20"
	 */
	@PostMapping("/stampede")
	public Map<String, Object> stampede(@RequestParam(defaultValue = "naive") String mode,
										@RequestParam(defaultValue = "20") int concurrency) throws Exception {

		String id = "hot-" + System.nanoTime(); // 매번 새 키 → 항상 빈 캐시에서 출발
		stampede.resetCounter();

		Function<String, String> call = switch (mode) {
			case "sync" -> stampede::synced;
			case "locked" -> stampede::locked;
			default -> stampede::naive;
		};

		long started = System.currentTimeMillis();
		runConcurrently(concurrency, () -> call.apply(id));
		long elapsed = System.currentTimeMillis() - started;

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", mode);
		result.put("동시요청", concurrency);
		result.put("실제_DB_로드_횟수", stampede.loaderCalls());
		result.put("총_소요시간ms", elapsed);
		result.put("설명", switch (mode) {
			case "sync" -> "cache.locking-writer=true 일 때만 1회. 기본값(non-locking)에서는 아무 효과가 없다.";
			case "locked" -> "키 단위 분산 락. 라이터 설정과 무관하게 항상 전체에서 1회.";
			default -> "대책 없음. 동시요청 수만큼 DB 를 때린다.";
		});
		return result;
	}

	/**
	 * ③ 관통: 존재하지 않는 id 를 여러 번 조회한다.
	 *
	 * curl "http://localhost:8080/api/cache-pitfalls/penetration?mode=strict&times=5"
	 * curl "http://localhost:8080/api/cache-pitfalls/penetration?mode=nullable&times=5"
	 */
	@GetMapping("/penetration")
	public Map<String, Object> penetration(@RequestParam(defaultValue = "strict") String mode,
										   @RequestParam(defaultValue = "5") int times) {

		Long missingId = 999_000L + (System.nanoTime() % 1000); // 확실히 없는 id
		penetration.resetCounter();

		for (int i = 0; i < times; i++) {
			try {
				if ("nullable".equals(mode)) {
					penetration.nullable(missingId);
				}
				else {
					penetration.strict(missingId);
				}
			}
			catch (RuntimeException ignored) {
				// strict 모드는 예외를 던진다. 그게 요점이다.
			}
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("mode", mode);
		result.put("조회한_없는_id", missingId);
		result.put("조회_횟수", times);
		result.put("실제_DB_조회_횟수", penetration.dbHits());
		result.put("설명", "nullable".equals(mode)
				? "null 을 짧은 TTL 로 캐싱하므로 첫 1회만 DB 를 친다."
				: "예외는 캐싱되지 않으므로 매번 DB 를 친다. 이게 관통이다.");
		return result;
	}

	/**
	 * ④ 트랜잭션: 캐시를 채운 뒤 evict + 롤백을 시킨다.
	 * transactionAware() 가 켜져 있으면 캐시가 살아남는다.
	 *
	 * curl -X POST "http://localhost:8080/api/cache-pitfalls/transaction"
	 */
	@PostMapping("/transaction")
	public Map<String, Object> transaction() {
		long id = System.nanoTime() % 100_000;

		tx.load(id);                       // 캐시 채우기
		boolean cachedBefore = txCached(id);

		String rollbackError = null;
		try {
			tx.evictThenRollback(id);      // evict 예약 후 예외 → 롤백
		}
		catch (RuntimeException ex) {
			rollbackError = ex.getMessage();
		}
		boolean cachedAfterRollback = txCached(id);

		tx.evictAndCommit(id);             // 이번엔 정상 커밋
		boolean cachedAfterCommit = txCached(id);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("id", id);
		result.put("1_로드직후_캐시있음", cachedBefore);
		result.put("2_롤백된_예외", rollbackError);
		result.put("3_롤백후_캐시있음", cachedAfterRollback);
		result.put("4_정상커밋후_캐시있음", cachedAfterCommit);
		result.put("설명", "3번이 true 여야 정상. transactionAware() 덕분에 롤백된 트랜잭션의 "
				+ "evict 는 아예 실행되지 않는다. 4번은 false 여야 한다.");
		return result;
	}

	/** ⑤ 키 설계: 실제로 어떤 키가 만들어졌는지 본다. v1:: 접두어가 붙어 있어야 한다. */
	@GetMapping("/keys")
	public Map<String, Object> keys() {
		Set<String> found = redis.keys("v1::*");
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("등록된_캐시", cacheManager.getCacheNames());
		result.put("실제_Redis_키", found);
		result.put("설명", "키 앞의 v1 은 CacheConfig.CACHE_VERSION. DTO 구조가 바뀌면 v2 로 올린다.");
		return result;
	}

	private boolean txCached(long id) {
		return redis.hasKey("v1::" + CacheNames.TX_DEMO + "::" + id);
	}

	/** N 개 스레드를 같은 순간에 출발시킨다. */
	private void runConcurrently(int threads, Runnable task) throws InterruptedException {
		CountDownLatch startGate = new CountDownLatch(1);
		CountDownLatch finished = new CountDownLatch(threads);

		try (ExecutorService pool = Executors.newFixedThreadPool(threads)) {
			for (int i = 0; i < threads; i++) {
				pool.submit(() -> {
					try {
						startGate.await();
						task.run();
					}
					catch (InterruptedException ex) {
						Thread.currentThread().interrupt();
					}
					finally {
						finished.countDown();
					}
				});
			}
			startGate.countDown(); // 동시 출발
			finished.await(30, TimeUnit.SECONDS);
		}
	}
}
