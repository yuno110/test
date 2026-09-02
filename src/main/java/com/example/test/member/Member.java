package com.example.test.member;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * JPA 엔티티. H2 의 member 테이블과 매핑된다.
 *
 * 이 객체를 캐시에 그대로 넣지 않는다는 점이 중요하다.
 * 엔티티는 영속성 컨텍스트에 묶여 있고 지연로딩 프록시를 물고 있을 수 있어서,
 * 캐시에는 MemberDto 로 변환해서 넣는다.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
public class Member {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(nullable = false, length = 100)
	private String name;

	@Column(nullable = false, unique = true)
	private String email;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	public Member(String name, String email) {
		this.name = name;
		this.email = email;
	}
}
