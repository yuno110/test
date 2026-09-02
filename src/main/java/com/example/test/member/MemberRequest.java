package com.example.test.member;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record MemberRequest(
		@NotBlank(message = "name is required") String name,
		@NotBlank @Email(message = "must be a valid email") String email) {
}
