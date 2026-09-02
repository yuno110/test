package com.example.test.redis;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.CommandLineRunner;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 부팅하면 자료구조별로 샘플 값을 한 번씩 넣어두는 러너.
 * 넣은 뒤 우분투에서 redis-cli 로 확인할 명령어를 그대로 로그에 찍어준다.
 *
 * 핵심: 문자열은 StringRedisTemplate 을 쓴다. (RedisTemplate 이 아니라)
 * RedisTemplate 은 기본이 JDK 직렬화라서 redis-cli 로 보면
 * "\xac\xed\x00\x05t\x00\x05hello" 같은 바이너리로 보인다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisDemoRunner implements CommandLineRunner {

	private final StringRedisTemplate redis;

	/** RedisConfig 에서 만든, 타입 정보(@class)를 같이 저장하는 JSON 템플릿 */
	private final RedisTemplate<String, Object> jsonRedisTemplate;

	@Override
	public void run(String... args) {
		ping();
		loadBasicTypes();
		loadObjects();
	}

	/** Redis 가 안 떠 있으면 여기서 바로 터진다. 나중에 엉뚱한 데서 헤매지 않으려고. */
	private void ping() {
		try (var connection = redis.getRequiredConnectionFactory().getConnection()) {
			log.info("Redis PING → {}", connection.ping());
		}
	}

	private void loadBasicTypes() {
		// 1) String : SET / GET / TTL
		redis.opsForValue().set("demo:string", "hello-redis");
		redis.opsForValue().set("demo:ttl", "60초 뒤에 사라짐", Duration.ofSeconds(60));

		// 2) Hash : 객체 한 건을 필드 단위로 저장할 때
		HashOperations<String, String, String> hash = redis.opsForHash();
		hash.putAll("demo:user:1", Map.of(
				"name", "yoon",
				"role", "student",
				"age", "20"));

		// 3) List : 큐/스택, 최근 목록
		redis.delete("demo:list");
		redis.opsForList().rightPushAll("demo:list", "first", "second", "third");

		// 4) Set : 중복 없는 집합
		redis.opsForSet().add("demo:tags", "redis", "java", "spring", "redis"); // redis 는 한 번만 들어감

		// 5) Sorted Set : 랭킹
		redis.opsForZSet().add("demo:rank", "alice", 100);
		redis.opsForZSet().add("demo:rank", "bob", 250);
		redis.opsForZSet().add("demo:rank", "carol", 175);

		// 6) 숫자 카운터 : INCR
		redis.opsForValue().increment("demo:counter");

		// 자바 쪽에서도 읽어보기 (redis-cli 결과와 같은지 비교용)
		String value = redis.opsForValue().get("demo:string");
		List<String> list = redis.opsForList().range("demo:list", 0, -1);

		log.info("""

				=========== 기본 자료구조 적재 완료 ===========
				 자바에서 읽은 값 : demo:string = {} / demo:list = {}

				 우분투(WSL) 터미널에서 redis-cli 로 확인해보세요:

				   redis-cli
				   > KEYS demo:*
				   > GET   demo:string
				   > TTL   demo:ttl
				   > HGETALL demo:user:1
				   > LRANGE  demo:list 0 -1
				   > SMEMBERS demo:tags
				   > ZREVRANGE demo:rank 0 -1 WITHSCORES
				   > GET demo:counter
				   > TYPE demo:list
				 ==============================================
				""", value, list);
	}

	/**
	 * 객체를 넣을 때 무슨 일이 일어나는지 두 방식을 나란히 비교한다.
	 *  - typed : RedisConfig 의 템플릿. 값 안에 @class 를 같이 저장 → 꺼내면 DemoMember 로 복원됨
	 *  - plain : 타입 정보 없이 JSON 만 저장  → 꺼내면 LinkedHashMap 으로 돌아옴 (캐스팅하면 ClassCastException)
	 */
	private void loadObjects() {
		DemoMember member = new DemoMember("yoon", 20, List.of("redis", "spring"));

		jsonRedisTemplate.opsForValue().set("demo:member:typed", member);
		jsonRedisTemplate.opsForHash().put("demo:members", "1", member);

		RedisTemplate<String, Object> plain = plainJsonTemplate();
		plain.opsForValue().set("demo:member:plain", member);

		Object typedResult = jsonRedisTemplate.opsForValue().get("demo:member:typed");
		Object plainResult = plain.opsForValue().get("demo:member:plain");

		// 같은 키를 String 템플릿으로 읽으면 redis-cli 의 GET 결과와 똑같은 원본이 보인다.
		String typedRaw = redis.opsForValue().get("demo:member:typed");
		String plainRaw = redis.opsForValue().get("demo:member:plain");

		log.info("""

				=========== 객체 직렬화 비교 ===========
				 [typed] 타입 정보 O  (RedisConfig 의 jsonRedisTemplate)
				   저장된 원본 : {}
				   꺼낸 타입   : {}
				   꺼낸 값     : {}

				 [plain] 타입 정보 X
				   저장된 원본 : {}
				   꺼낸 타입   : {}   ← DemoMember 로 캐스팅하면 ClassCastException
				   꺼낸 값     : {}

				 redis-cli 확인:
				   > GET demo:member:typed
				   > GET demo:member:plain
				   > HGETALL demo:members
				 ========================================
				""",
				typedRaw, typedResult.getClass().getName(), typedResult,
				plainRaw, plainResult.getClass().getName(), plainResult
		);
	}

	/**
	 * 비교 전용 템플릿. enableDefaultTyping 을 켜지 않은 것 말고는 RedisConfig 와 같다.
	 * 빈으로 등록하면 RedisTemplate<String, Object> 후보가 둘이 되어 주입이 모호해지므로 여기서만 만들어 쓴다.
	 */
	private RedisTemplate<String, Object> plainJsonTemplate() {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(redis.getRequiredConnectionFactory());
		template.setKeySerializer(new StringRedisSerializer());
		template.setValueSerializer(GenericJacksonJsonRedisSerializer.builder().build());
		template.afterPropertiesSet(); // 빈이 아니라서 초기화를 직접 호출해야 한다
		return template;
	}
}
