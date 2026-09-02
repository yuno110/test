package com.example.test.config;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheManager.RedisCacheManagerBuilder;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.cache.RedisCacheWriter.TtlFunction;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import com.example.test.cache.CacheNames;

/**
 * @EnableCaching 이 있어야 @Cacheable / @CacheEvict 가 동작한다.
 * (프록시를 만들어 메서드 호출을 가로채는 스위치. 이게 없으면 애노테이션은 그냥 주석이다)
 *
 * 여기서 캐시 함정 6가지 중 4가지에 대응한다.
 *   ① TTL      : entryTtl 로 반드시 만료시킨다
 *   ② 스탬피드 : TTL 에 지터를 섞어 만료 시점을 흩뿌린다
 *   ④ 트랜잭션 : transactionAware() 로 캐시 연산을 커밋 이후로 미룬다
 *   ⑤ 키 설계  : 캐시 키에 스키마 버전을 붙인다
 *   ⑥ 직렬화   : JDK 대신 JSON (redis-cli 로 읽힌다)
 */
@Configuration
@EnableCaching
public class CacheConfig {

	/**
	 * ⑤ 캐시 키 스키마 버전.
	 * DTO 에 필드를 추가하거나 타입을 바꾸는 배포에서 이 값을 올리면,
	 * 옛 구조로 저장된 값은 조회 자체가 안 되고 TTL 로 알아서 사라진다.
	 * 캐시를 수동으로 비우거나 무중단 배포 중 구/신 버전이 같은 키를 다투는 사고를 막는다.
	 */
	private static final String CACHE_VERSION = "v1";

	private static final Duration DEFAULT_TTL = Duration.ofSeconds(60);

	@Bean
	public RedisCacheConfiguration redisCacheConfiguration(GenericJacksonJsonRedisSerializer jsonSerializer) {
		return RedisCacheConfiguration.defaultCacheConfig()
				// ① + ② TTL 은 필수, 게다가 ±20% 흔들어서 동시 만료를 막는다
				.entryTtl(jitter(DEFAULT_TTL, 0.2))
				.disableCachingNullValues()
				// ⑤ 키 앞에 "v1::캐시이름::" 이 붙는다 → v1::members::1
				.computePrefixWith(cacheName -> CACHE_VERSION + "::" + cacheName + "::")
				// 키는 문자열 그대로 → redis-cli 에서 그대로 읽힌다
				.serializeKeysWith(SerializationPair.fromSerializer(new StringRedisSerializer()))
				// ⑥ 값은 RedisConfig 와 같은 JSON 직렬화기 재사용
				.serializeValuesWith(SerializationPair.fromSerializer(jsonSerializer));
	}

	/**
	 * 캐시별로 다른 정책을 주는 자리. 기본 설정(위 빈)을 받아 필요한 부분만 바꾼다.
	 */
	@Bean
	public RedisCacheManagerBuilderCustomizer cacheCustomizer(RedisCacheConfiguration defaults) {
		return builder -> builder
				// ④ 캐시 연산을 트랜잭션 커밋 이후로 미룬다. 롤백되면 캐시는 건드려지지 않는다.
				.transactionAware()
				// ③ 관통 대응 캐시: null 도 저장하되 TTL 을 아주 짧게 준다.
				//    (없는 데이터를 계속 조회당해도 DB 까지 가지 않게 하되, 곧 생길 수도 있으니 10초만)
				.withCacheConfiguration(CacheNames.PENETRATION_NULLABLE,
						RedisCacheConfiguration.defaultCacheConfig()
								.entryTtl(Duration.ofSeconds(10))
								.computePrefixWith(name -> CACHE_VERSION + "::" + name + "::")
								.serializeKeysWith(defaults.getKeySerializationPair())
								.serializeValuesWith(defaults.getValueSerializationPair()))
				// 스탬피드 실습은 짧은 TTL 이 편하다
				.withCacheConfiguration(CacheNames.STAMPEDE_NAIVE, defaults.entryTtl(Duration.ofSeconds(30)))
				.withCacheConfiguration(CacheNames.STAMPEDE_SYNC, defaults.entryTtl(Duration.ofSeconds(30)));
	}

	/**
	 * ② (대안) Spring 이 제공하는 "락킹 캐시 라이터".
	 *
	 * 중요: @Cacheable(sync = true) 는 이 라이터를 써야만 실제로 동작한다.
	 * Boot 의 기본 캐시매니저는 non-locking 라이터라서 sync = true 를 붙여도 아무 효과가 없다.
	 * (RedisCache.get(key, loader) 내부가 isLockingCacheWriter() 일 때만 락을 잡는다)
	 *
	 * 기본값을 꺼둔 이유: 이 락은 "키 단위"가 아니라 "캐시 이름 단위"다.
	 * 어떤 키 하나를 로딩하는 동안 그 캐시 전체가 잠긴다.
	 * 그래서 보통은 RedisLock 처럼 키 단위 분산 락을 직접 쓰는 쪽이 낫다.
	 *
	 * 켜기: --cache.locking-writer=true
	 */
	@Bean
	@ConditionalOnProperty(name = "cache.locking-writer", havingValue = "true")
	public RedisCacheManager lockingCacheManager(RedisConnectionFactory connectionFactory,
			RedisCacheConfiguration defaults,
			ObjectProvider<RedisCacheManagerBuilderCustomizer> customizers) {

		RedisCacheManagerBuilder builder = RedisCacheManager
				.builder(RedisCacheWriter.lockingRedisCacheWriter(connectionFactory))
				.cacheDefaults(defaults);
		customizers.orderedStream().forEach(customizer -> customizer.customize(builder));
		return builder.build();
	}

	/**
	 * ② TTL 지터. 같은 순간에 캐싱된 키들이 같은 순간에 만료되면,
	 * 그 찰나에 들어온 모든 요청이 한꺼번에 DB 로 몰린다(Thundering Herd).
	 * 만료 시각을 ±ratio 만큼 무작위로 흩뿌려서 몰림을 평탄화한다.
	 */
	private static TtlFunction jitter(Duration base, double ratio) {
		return (key, value) -> {
			long baseMillis = base.toMillis();
			long spread = (long) (baseMillis * ratio);
			long delta = ThreadLocalRandom.current().nextLong(-spread, spread + 1);
			return Duration.ofMillis(baseMillis + delta);
		};
	}
}
