package com.example.test.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import com.example.test.member.MemberRequest;
import com.example.test.member.MemberService;

/**
 * @Cacheable 기본 동작. 실제 도메인 캐시인 MemberService.findById 로 본다.
 *
 *   findById(1)
 *    ├ Redis 에 v1::members::1 이 있다 → 그 값을 바로 돌려준다 (메서드 본문을 아예 안 탄다)
 *    └ 없다 → 본문 실행 (DB 조회 + 일부러 0.3초 대기) → 결과를 Redis 에 저장 → 돌려준다
 *
 * 규칙(TTL, 키 모양, JSON)은 config/CacheConfig 에서 정한다.
 * 키: v1::members::{id}
 */
class CacheBasicsTest extends CacheTestSupport {

	@Autowired
	private MemberService memberService;

	@Autowired
	private CacheManager cacheManager;

	@Test
	void 첫_조회는_본문을_타서_느리고_두_번째는_캐시에서_꺼내서_빠르다() {
		long 첫번째 = 걸린_ms(() -> memberService.findById(1L));   // 미스 → 본문 실행 (0.3초 대기 포함)
		long 두번째 = 걸린_ms(() -> memberService.findById(1L));   // 히트 → 본문을 안 탄다

		assertThat(첫번째).isGreaterThanOrEqualTo(300);
		assertThat(두번째).isLessThan(300);
	}

	@Test
	void 캐시_키는_버전_캐시이름_id_모양이고_값은_JSON이다() {
		memberService.findById(1L);

		assertThat(redis.opsForValue().get("v1::members::1"))      // GET v1::members::1
				.contains("\"@class\":\"com.example.test.member.MemberDto\"")
				.contains("\"name\":\"yoon\"");
	}

	@Test
	void TTL은_60초에서_위아래로_20퍼센트_흩어진다() {
		memberService.findById(1L);

		assertThat(redis.getExpire("v1::members::1"))              // TTL v1::members::1
				.isBetween(48L, 72L);
	}

	@Test
	void 수정하면_캐시가_지워지고_다음_조회에서_새_값을_싣는다() {
		memberService.findById(2L);
		assertThat(redis.hasKey("v1::members::2")).isTrue();

		memberService.update(2L, new MemberRequest("kim-updated", "kim@example.com"));   // @CacheEvict
		assertThat(redis.hasKey("v1::members::2")).isFalse();

		assertThat(memberService.findById(2L).name()).isEqualTo("kim-updated");
	}

	@Test
	void null_금지_캐시에_null을_넣으면_건너뛰지_않고_에러가_난다() {
		Cache members = cacheManager.getCache(CacheNames.MEMBERS);

		assertThatThrownBy(() -> members.put(999L, null))
				.isInstanceOf(IllegalArgumentException.class);

		// @Cacheable 메서드가 null 을 반환해도 똑같이 터진다.
		// 그래서 MemberService.findById 는 없는 id 면 null 대신 예외를 던진다
	}
}
