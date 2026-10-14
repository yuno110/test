package com.example.test.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * 직렬화 : 자바 객체를 Redis 에 넣을 "글자"로 바꾸는 방식. (config/RedisConfig 참고)
 *
 *   JDK         (Boot 기본 redisTemplate) → \xac\xed\x00\x05...                       사람이 못 읽는다
 *   JSON + 이름표 (jsonRedisTemplate)      → {"@class":"...DemoMember","name":"yoon",...}  원래 클래스로 복원
 *   JSON 만     (이름표_없는_템플릿)          → {"name":"yoon",...}                         Map 으로 복원
 *
 * 키: test:SerializationTest
 */
class SerializationTest extends RedisTestSupport {

	/** Spring Boot 가 자동으로 만든 템플릿. 직렬화 설정이 없어서 JDK 직렬화를 쓴다 */
	@Autowired
	private RedisTemplate<Object, Object> redisTemplate;

	/** RedisConfig 에서 만든 템플릿. 키는 글자, 값은 JSON + 이름표(@class) */
	@Autowired
	private RedisTemplate<String, Object> jsonRedisTemplate;

	private final DemoMember member = new DemoMember("yoon", 20, List.of("redis", "spring"));

	@Test
	void JDK_직렬화는_키_이름까지_바뀌어서_redis_cli로_못_찾는다() {
		redisTemplate.opsForValue().set(key, "hello");

		// 같은 템플릿으로는 잘 읽힌다
		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo("hello");

		// 하지만 "test:SerializationTest" 라는 이름의 키는 없다.
		// 실제 키 이름 앞에 \xac\xed... 가 붙어 있다 → redis-cli: KEYS *SerializationTest
		assertThat(redis.hasKey(key)).isFalse();
	}

	@Test
	void 이름표가_있으면_꺼낼_때_원래_클래스로_돌아온다() {
		jsonRedisTemplate.opsForValue().set(key, member);

		// 실제로 저장된 글자. redis-cli 로 GET 한 결과와 같다
		assertThat(redis.opsForValue().get(key))
				.contains("\"@class\":\"com.example.test.redis.DemoMember\"");

		assertThat(jsonRedisTemplate.opsForValue().get(key))
				.isInstanceOf(DemoMember.class)
				.isEqualTo(member);
	}

	@Test
	void 이름표가_없으면_꺼낼_때_Map으로_돌아온다() {
		RedisTemplate<String, Object> plain = 이름표_없는_템플릿();
		plain.opsForValue().set(key, member);

		assertThat(redis.opsForValue().get(key)).doesNotContain("@class");

		// 무슨 클래스였는지 모르니 그냥 Map 으로 준다. (DemoMember) 로 캐스팅하면 ClassCastException
		assertThat(plain.opsForValue().get(key)).isInstanceOf(LinkedHashMap.class);
	}

	@Test
	void Hash는_칸_이름은_글자로_칸_내용은_JSON으로_저장된다() {
		// RedisConfig 의 setHashKeySerializer(글자), setHashValueSerializer(JSON) 가 쓰인다
		jsonRedisTemplate.opsForHash().put(key, "1", member);     // HSET key 1 {...}

		// 글자 템플릿으로 칸 이름 "1" 을 그대로 찾을 수 있고, 내용은 JSON 이다
		HashOperations<String, String, String> raw = redis.opsForHash();
		assertThat(raw.get(key, "1"))                             // HGET key 1
				.contains("\"@class\":\"com.example.test.redis.DemoMember\"");
	}

	/**
	 * 비교용. RedisConfig 와 같은데 이름표(enableDefaultTyping)만 껐다.
	 * 이 테스트에서만 쓰니 빈으로 만들지 않고 직접 만든다. 그래서 초기화(afterPropertiesSet)도 직접 부른다.
	 */
	private RedisTemplate<String, Object> 이름표_없는_템플릿() {
		RedisTemplate<String, Object> template = new RedisTemplate<>();
		template.setConnectionFactory(redis.getRequiredConnectionFactory());
		template.setKeySerializer(new StringRedisSerializer());
		template.setValueSerializer(GenericJacksonJsonRedisSerializer.builder().build());
		template.afterPropertiesSet();
		return template;
	}
}
