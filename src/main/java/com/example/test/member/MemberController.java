package com.example.test.member;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** JPA + Redis 캐시 실험용 CRUD. */
@RestController
@RequestMapping("/api/members")
@RequiredArgsConstructor
public class MemberController {

	private final MemberService service;

	@GetMapping
	public List<MemberDto> list(@RequestParam(required = false) String name) {
		return (name == null) ? service.findAll() : service.searchByName(name);
	}

	/** 같은 id 로 두 번 호출해 보면 두 번째부터 캐시 히트로 빨라진다. */
	@GetMapping("/{id}")
	public MemberDto get(@PathVariable Long id) {
		return service.findById(id);
	}

	@PostMapping
	@ResponseStatus(HttpStatus.CREATED)
	public MemberDto create(@Valid @RequestBody MemberRequest request) {
		return service.create(request);
	}

	@PutMapping("/{id}")
	public MemberDto update(@PathVariable Long id, @Valid @RequestBody MemberRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@ResponseStatus(HttpStatus.NO_CONTENT)
	public void delete(@PathVariable Long id) {
		service.delete(id);
	}

	@DeleteMapping("/cache")
	public Map<String, String> evictCache() {
		service.evictAll();
		return Map.of("status", "members cache cleared");
	}

	@ExceptionHandler(NoSuchElementException.class)
	ProblemDetail handleNotFound(NoSuchElementException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail handleBadRequest(IllegalArgumentException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
	}
}
