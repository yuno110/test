package com.example.test.member;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface MemberRepository extends JpaRepository<Member, Long> {

	// 메서드 이름만으로 SQL 이 만들어진다: where lower(name) like lower('%?%')
	List<Member> findByNameContainingIgnoreCase(String name);

	boolean existsByEmail(String email);
}
