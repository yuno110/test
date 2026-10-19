package com.example.test.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * ③ 관통 : 없는 데이터를 조회하면 캐시에 아무것도 안 남는다. 그래서 매번 DB 까지 간다.
 *
 * PenetrationService 가 DB 를 몇 번 쳤는지(dbHits) 센다. 회원 999번은 없다.
 * 키: v1::penetrationNullable::999
 */
class PenetrationTest extends CacheTestSupport {

	private static final long 없는_id = 999L;

	@Autowired
	private PenetrationService penetration;

	@BeforeEach
	void 준비() {
		penetration.resetCounter();
	}

	@Test
	void 없을_때_예외를_던지면_매번_DB까지_간다() {
		for (int i = 0; i < 5; i++) {
			assertThatThrownBy(() -> penetration.strict(없는_id))
					.isInstanceOf(NoSuchElementException.class);
		}

		// 예외는 캐시에 안 남는다 → 5번 모두 DB
		assertThat(penetration.dbHits()).isEqualTo(5);
	}

	@Test
	void 없음을_null로_캐싱하면_첫_번째만_DB까지_간다() {
		for (int i = 0; i < 5; i++) {
			assertThat(penetration.nullable(없는_id)).isNull();
		}

		assertThat(penetration.dbHits()).isEqualTo(1);

		// "없음" 표시가 10초짜리로 저장돼 있다 → redis-cli: GET v1::penetrationNullable::999
		// 값이 JSON 이 아니라 \xac\xed...NullValue 로 깨져 보인다.
		// 값 직렬화를 JSON 으로 정해도, Spring 은 이 "없음" 표시만은 항상 JDK 방식으로 저장한다
		// "없음" 표시가 몇 초 뒤에 사라질 예정인지 본다 (TTL v1::penetrationNullable::999)
		// CacheConfig 에서 이 캐시만 TTL 10초로 짧게 줬다. 999번 회원이 곧 가입할 수도 있으니 "없음"을 오래 기억하면 안 된다
		// 저장 후 시간이 조금 흘렀으니 10 또는 9 가 나온다. -1(영원히 남음)이나 -2(키 없음)면 실패
		assertThat(redis.getExpire("v1::penetrationNullable::999")).isBetween(1L, 10L);
	}
}
