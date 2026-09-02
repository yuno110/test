package com.example.test.member;

import java.util.List;
import java.util.NoSuchElementException;

import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 캐시 애노테이션이 붙는 자리. 컨트롤러가 아니라 서비스에 붙이는 게 정석이다.
 *
 * 주의: 캐시는 프록시가 가로채서 동작한다. 그래서 이 클래스 안에서 findById() 를
 * 직접 호출하면(self-invocation) 캐시가 먹지 않는다. 항상 밖에서 들어오는 호출이어야 한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberService {

	private final MemberRepository repository;

	@Transactional(readOnly = true)
	public List<MemberDto> findAll() {
		return repository.findAll().stream().map(MemberDto::from).toList();
	}

	/**
	 * 캐시 실험용. 일부러 300ms 를 재워놨으므로 두 번째 호출부터 눈에 띄게 빨라진다.
	 *
	 * 동작: members::{id} 키를 Redis 에서 먼저 찾고, 있으면 메서드 본문을 아예 실행하지 않는다.
	 * 없으면 본문을 실행한 뒤 반환값을 그 키에 저장한다.
	 */
	@Cacheable(cacheNames = "members", key = "#id")
	@Transactional(readOnly = true)
	public MemberDto findById(Long id) {
		log.info("CACHE MISS -> DB 조회 member {}", id);
		sleepQuietly();
		return repository.findById(id)
				.map(MemberDto::from)
				.orElseThrow(() -> new NoSuchElementException("member not found: " + id));
	}

	@Transactional(readOnly = true)
	public List<MemberDto> searchByName(String name) {
		return repository.findByNameContainingIgnoreCase(name).stream().map(MemberDto::from).toList();
	}

	@Transactional
	public MemberDto create(MemberRequest request) {
		if (repository.existsByEmail(request.email())) {
			throw new IllegalArgumentException("email already taken: " + request.email());
		}
		return MemberDto.from(repository.save(new Member(request.name(), request.email())));
	}

	/**
	 * 수정하면 캐시에 남은 옛 값을 지운다. 다음 조회에서 미스가 나면서 최신값을 다시 싣는다.
	 *
	 * 대안으로 @CachePut 이 있다. 본문을 반드시 실행하고 반환값으로 캐시를 "덮어쓰는" 것이라
	 * 수정 직후 조회가 확실하면 미스를 한 번 아낀다. 다만 "DB에 저장된 값 == 메서드 반환값"이
	 * 성립해야 하므로(DB 기본값·트리거·다른 트랜잭션 개입이 있으면 깨진다) 보통은 evict 가 안전하다.
	 */
	@CacheEvict(cacheNames = "members", key = "#id")
	@Transactional
	public MemberDto update(Long id, MemberRequest request) {
		Member member = repository.findById(id)
				.orElseThrow(() -> new NoSuchElementException("member not found: " + id));
		member.setName(request.name());
		member.setEmail(request.email());
		return MemberDto.from(member); // 더티 체킹으로 flush 된다. save() 호출이 필요 없다.
	}

	/** 삭제는 캐시에 남겨둘 값이 없으니 evict 가 맞다. */
	@CacheEvict(cacheNames = "members", key = "#id")
	@Transactional
	public void delete(Long id) {
		repository.deleteById(id);
	}

	@CacheEvict(cacheNames = "members", allEntries = true)
	public void evictAll() {
		log.info("'members' 캐시 전체 삭제");
	}

	private void sleepQuietly() {
		try {
			Thread.sleep(300);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}
}
