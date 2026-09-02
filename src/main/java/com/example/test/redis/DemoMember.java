package com.example.test.redis;

import java.util.List;

/**
 * JSON 직렬화 실습용 도메인 객체.
 *
 * 이 클래스의 "패키지 + 클래스명"이 Redis 에 저장되는 값 안에 @class 로 같이 들어간다.
 * 즉 이 클래스를 다른 패키지로 옮기면, 이미 저장돼 있던 값은 더 이상 역직렬화되지 않는다.
 */
public record DemoMember(String name, int age, List<String> tags) {
}
