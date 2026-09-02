package com.example.test.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import tools.jackson.databind.jsontype.PolymorphicTypeValidator;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> jsonRedisTemplate(RedisConnectionFactory cf) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(cf);
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer());
        template.setHashValueSerializer(jsonSerializer());
        return template;
    }

    // 값에 타입 정보(@class)를 같이 저장해서 꺼낼 때 원래 타입으로 복원한다.
    // @Bean 으로 빼둔 이유: 캐시(CacheConfig)도 같은 직렬화 규칙을 써야
    // redis-cli 에서 캐시 값도 똑같이 JSON 으로 읽히기 때문.
    // 역직렬화 가젯을 막기 위해 우리 패키지 타입만 허용한다.
    @Bean
    public GenericJacksonJsonRedisSerializer jsonSerializer() {
        PolymorphicTypeValidator validator = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.example.test.")
                .allowIfSubType("java.util.")
                .build();
        return GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(validator)
                .build();
    }
}
