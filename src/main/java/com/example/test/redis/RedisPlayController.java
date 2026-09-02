package com.example.test.redis;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * 원하는 타이밍에 값을 넣고 빼보기 위한 실습용 엔드포인트.
 * 브라우저나 curl 로 찌른 뒤 → 우분투 redis-cli 로 결과 확인하는 흐름.
 */
@RestController
@RequestMapping("/redis")
@RequiredArgsConstructor
public class RedisPlayController {

	private final StringRedisTemplate redis;

	/** RedisConfig 에서 만든, 타입 정보(@class)를 같이 저장하는 JSON 템플릿 */
	private final RedisTemplate<String, Object> jsonRedisTemplate;

	// SET key value [EX seconds]
	// curl -X POST "http://localhost:8080/redis/string?key=name&value=yoon&ttlSeconds=30"
	@PostMapping("/string")
	public String set(@RequestParam String key,
					  @RequestParam String value,
					  @RequestParam(required = false) Long ttlSeconds) {
		if (ttlSeconds == null) {
			redis.opsForValue().set(key, value);
		} else {
			redis.opsForValue().set(key, value, Duration.ofSeconds(ttlSeconds));
		}
		return "SET %s = %s (ttl=%s) → redis-cli 에서 GET %s".formatted(key, value, ttlSeconds, key);
	}

	// GET key
	@GetMapping("/string/{key}")
	public String get(@PathVariable String key) {
		String value = redis.opsForValue().get(key);
		return value == null ? "(nil) — 없는 키거나 TTL 로 만료됨" : value;
	}

	// TTL key : -1 은 만료 없음, -2 는 키 자체가 없음
	@GetMapping("/ttl/{key}")
	public Long ttl(@PathVariable String key) {
		return redis.getExpire(key);
	}

	// HSET key field value
	@PostMapping("/hash")
	public String hset(@RequestParam String key,
					   @RequestParam String field,
					   @RequestParam String value) {
		HashOperations<String, String, String> hash = redis.opsForHash();
		hash.put(key, field, value);
		return "HSET %s %s %s → redis-cli 에서 HGETALL %s".formatted(key, field, value, key);
	}

	@GetMapping("/hash/{key}")
	public Map<String, String> hgetAll(@PathVariable String key) {
		HashOperations<String, String, String> hash = redis.opsForHash();
		return hash.entries(key);
	}

	// RPUSH key value
	@PostMapping("/list")
	public Long rpush(@RequestParam String key, @RequestParam String value) {
		return redis.opsForList().rightPush(key, value);
	}

	@GetMapping("/list/{key}")
	public List<String> lrange(@PathVariable String key) {
		return redis.opsForList().range(key, 0, -1);
	}

	// ZADD key score member → 랭킹
	@PostMapping("/rank")
	public Boolean zadd(@RequestParam String key,
						@RequestParam String member,
						@RequestParam double score) {
		return redis.opsForZSet().add(key, member, score);
	}

	// 점수 높은 순 (redis-cli: ZREVRANGE key 0 -1 WITHSCORES)
	@GetMapping("/rank/{key}")
	public Set<String> topRank(@PathVariable String key) {
		return redis.opsForZSet().reverseRange(key, 0, -1);
	}

	// INCR key : 조회수/카운터. 원자적으로 증가한다.
	@PostMapping("/incr/{key}")
	public Long incr(@PathVariable String key) {
		return redis.opsForValue().increment(key);
	}

	// KEYS 는 전체 스캔이라 운영에서는 금지. 실습이니까 쓰는 것.
	@GetMapping("/keys")
	public Set<String> keys(@RequestParam(defaultValue = "*") String pattern) {
		return redis.keys(pattern);
	}

	// DEL key
	@DeleteMapping("/{key}")
	public Boolean delete(@PathVariable String key) {
		return redis.delete(key);
	}

	// ---------------------------------------------------------------
	// 객체(JSON) 실습
	// ---------------------------------------------------------------

	// curl -X POST "http://localhost:8080/redis/member?key=member:1&name=yoon&age=20&tags=redis,spring"
	@PostMapping("/member")
	public String saveMember(@RequestParam String key,
							 @RequestParam String name,
							 @RequestParam int age,
							 @RequestParam(required = false) List<String> tags) {
		jsonRedisTemplate.opsForValue().set(key, new DemoMember(name, age, tags == null ? List.of() : tags));
		// 방금 저장된 원본을 그대로 돌려준다. redis-cli 의 GET 결과와 같다.
		return redis.opsForValue().get(key);
	}

	/**
	 * 꺼낼 때 어떤 자바 타입으로 복원됐는지까지 같이 보여준다.
	 * @class 가 붙어 있으면 DemoMember, 없으면 LinkedHashMap 이 나온다.
	 */
	@GetMapping("/member/{key}")
	public Map<String, Object> member(@PathVariable String key) {
		Object loaded = jsonRedisTemplate.opsForValue().get(key);

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("javaType", loaded == null ? null : loaded.getClass().getName());
		result.put("value", loaded);
		return result;
	}

	/** 어떤 키든 redis-cli 의 GET 처럼 "저장된 원본 문자열" 그대로 보기 */
	@GetMapping("/raw/{key}")
	public String raw(@PathVariable String key) {
		String value = redis.opsForValue().get(key);
		return value == null ? "(nil)" : value;
	}
}
