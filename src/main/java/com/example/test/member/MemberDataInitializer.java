package com.example.test.member;

import java.util.List;

import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 인메모리 H2 라서 앱을 끄면 데이터가 사라진다. 매 기동마다 실습용 회원 몇 명을 넣어둔다.
 * (CommandLineRunner 는 스프링이 빈을 전부 만든 뒤에 돈다. 그래서 repository 를 안심하고 쓸 수 있다)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemberDataInitializer implements CommandLineRunner {

	private final MemberRepository repository;

	@Override
	public void run(String... args) {
		if (repository.count() > 0) {
			return;
		}
		repository.saveAll(
				List.of(
						new Member("yoon", "yoon@example.com"),
						new Member("kim", "kim@example.com"),
						new Member("lee", "lee@example.com")
				)
		);

		log.info(
				"""

				=========== H2 회원 데이터 적재 완료 ===========
				 GET  http://localhost:8080/api/members
				 GET  http://localhost:8080/api/members/1     ← 두 번 호출해서 응답속도 비교
				 H2 콘솔: http://localhost:8080/h2-console
				   JDBC URL: jdbc:h2:mem:testdb   /   User: sa   /   Password: (비움)

				 redis-cli 에서 캐시 확인 (위 /api/members/1 을 먼저 호출해야 키가 생긴다.
				 키 앞의 v1:: 은 CacheConfig 의 CACHE_VERSION):
				   > KEYS v1::members::*
				   > GET  v1::members::1
				   > TTL  v1::members::1
				 ==============================================
				"""
		);
	}
}
