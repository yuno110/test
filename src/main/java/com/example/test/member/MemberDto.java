package com.example.test.member;

import java.time.LocalDateTime;

/**
 * 캐시와 API 응답에 쓰이는 값 객체.
 *
 * 원본 spring-lab 에서는 Serializable 을 구현했다. RedisCacheManager 의 기본 직렬화가
 * JDK 직렬화라서 그게 필수였기 때문인데, 여기서는 CacheConfig 가 JSON 직렬화를 쓰도록
 * 바꿔놨으므로 Serializable 이 필요 없다. 대신 redis-cli 로 캐시 값이 읽힌다.
 */
public record MemberDto(Long id, String name, String email, LocalDateTime createdAt) {

	public static MemberDto from(Member member) {
		return new MemberDto(member.getId(), member.getName(), member.getEmail(), member.getCreatedAt());
	}
}
