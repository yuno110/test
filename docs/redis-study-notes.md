# Redis 공부 정리

이 프로젝트는 Redis 공부용이다. 두 가지를 다룬다.

1. **Redis 기본 기능**: 자료구조(String, List, Hash, Set, Sorted Set), TTL, 카운터, 직렬화
2. **Redis를 캐시로 쓸 때의 함정과 대응**: TTL, 스탬피드, 관통, 트랜잭션, 키 버전, 직렬화

모든 내용은 **테스트로 확인할 수 있다**. 설명을 읽다가 헷갈리면 해당 테스트를 돌려보고 redis-cli로 키를 직접 보면 된다.

---

## 1. 프로젝트 구조

```
src/main/java/com/example/test
├ config/
│  ├ RedisConfig.java      RedisTemplate 과 JSON 직렬화기 설정
│  └ CacheConfig.java      캐시 규칙 (TTL, 키 모양, 직렬화, 트랜잭션, 캐시별 예외 규칙)
├ member/                  실제 도메인 캐시 예제 (회원 조회를 @Cacheable 로 캐싱)
│  ├ MemberService.java    findById 에 @Cacheable, update/delete 에 @CacheEvict
│  ├ MemberController.java curl 로 캐시 속도 체감용 (/api/members)
│  └ MemberDataInitializer 앱 시작 시 회원 3명(1 yoon, 2 kim, 3 lee) 넣기
└ cache/                   캐시 함정 실습용 서비스
   ├ StampedeService.java          ② 스탬피드 (naive / sync / 분산락)
   ├ RedisLock.java                ② 직접 만든 분산 락
   ├ PenetrationService.java       ③ 관통 (strict / nullable)
   ├ TransactionalCacheService     ④ 트랜잭션과 캐시
   └ CacheNames.java               캐시 이름 상수

src/test/java/com/example/test
├ redis/   Redis 기본 기능 학습 테스트 (@DataRedisTest, Redis 만 띄움)
└ cache/   캐시 함정 학습 테스트 (@SpringBootTest, 앱 전체를 띄움)
```

정리하면, **main 은 "Redis 를 캐시로 쓰는 코드"**, **test/redis 는 "Redis 자체 기능"**, **test/cache 는 "캐시 함정 확인"** 이다.

### 환경

- Spring Boot 4.1.1, Spring Data Redis 4.1.1 (Lettuce), Jackson 3, Java 21
- DB: H2 인메모리 (앱을 끄면 사라짐), JPA
- Redis 8: WSL Ubuntu 에서 실행, Windows 에서 `localhost:6379` 로 접속

### 실행

```powershell
.\gradlew.bat test                                          # 전체 테스트
.\gradlew.bat test --tests "com.example.test.redis.*"       # Redis 기본 기능만
.\gradlew.bat test --tests "com.example.test.cache.*"       # 캐시만
.\gradlew.bat bootRun                                       # 앱 실행 (8080)
.\gradlew.bat bootRun --args='--cache.locking-writer=true'  # Spring 캐시 락 켜고 실행
```

테스트는 **시작할 때** 자기 키를 지우고, **끝날 때는 남겨둔다**. 테스트 하나를 돌린 직후 redis-cli 로 결과를 볼 수 있다.

---

## 2. WSL 과 Redis 켜고 끄기

| 하고 싶은 것 | 명령 (PowerShell) |
|---|---|
| WSL 상태 보기 | `wsl -l -v` |
| Ubuntu 들어가기 | `wsl -d Ubuntu` |
| Ubuntu 만 끄기 | `wsl --terminate Ubuntu` |
| WSL 전부 끄기 (Docker Desktop 도 멈춤) | `wsl --shutdown` |
| Redis 끄기 (밖에서) | `wsl -d Ubuntu -u root -- systemctl stop redis-server` |

Ubuntu 안에서:

| 하고 싶은 것 | 명령 |
|---|---|
| Redis 켜기 / 끄기 / 상태 | `sudo systemctl start / stop / status redis-server` |
| 부팅 시 자동 시작 켜기 / 끄기 | `sudo systemctl enable / disable redis-server` |
| 살아있는지 | `redis-cli ping` → `PONG` |

---

## 3. RedisTemplate 설정 (`RedisConfig`)

Boot 는 Redis 의존성만 넣어도 템플릿 두 개를 자동으로 만든다.

| 빈 | 키/값 저장 방식 | 특징 |
|---|---|---|
| `redisTemplate` | JDK 직렬화 | redis-cli 로 보면 `\xac\xed...` 로 깨져 보임 |
| `stringRedisTemplate` | 문자열 | 문자열만 다룰 때 편함 |

우리는 **자바 객체를 JSON 으로 저장하는** `jsonRedisTemplate` 을 하나 더 만들었다. (Boot 템플릿을 대체하는 게 아니라 추가)

직렬화 설정 4개는 서랍장으로 이해하면 된다.

| 설정 | 무엇을 | 우리 설정 |
|---|---|---|
| `keySerializer` | 키 이름 | 문자열 |
| `valueSerializer` | 값 | JSON |
| `hashKeySerializer` | Hash 안의 칸 이름 | 문자열 |
| `hashValueSerializer` | Hash 안의 칸 내용 | JSON |

Hash 는 **키 하나에 Map 하나**다. `user:1` → `{name: yoon, age: 30, role: admin}`

### JSON 직렬화기 (`GenericJacksonJsonRedisSerializer`)

- `enableDefaultTyping(...)`: 값에 `"@class":"com.example.test..."` 이름표를 같이 저장한다. 꺼낼 때 원래 클래스로 돌아온다. 이름표가 없으면 `Map` 으로 돌아온다. (`SerializationTest`)
- `allowIfSubType("com.example.test.")`: 이름표로 아무 클래스나 만들 수 있으면 보안 구멍이 되므로, 우리 패키지와 `java.util.` 만 허용한다.
- `@Bean` 으로 뺀 이유: `CacheConfig` 도 같은 직렬화기를 써서 캐시 값도 JSON 으로 보이게 하려고.

---

## 4. Redis 기본 기능 (test/redis)

| 테스트 | 배우는 것 | 쓰임 |
|---|---|---|
| `StringTest` | set/get, 덮어쓰기, `setIfAbsent`(SET NX) | 단순 값, 락 |
| `TtlTest` | TTL, `getExpire`, **TTL 없이 다시 set 하면 만료가 풀린다** | 세션, 인증번호 |
| `CounterTest` | `increment`(INCR). 읽고-더하고-쓰기는 동시 요청에서 숫자가 빠진다 | 조회수, 재고 |
| `ListTest` | rightPush(뒤에), leftPush(앞에), 음수 번호, 큐, 최근 본 목록 | 큐, 타임라인 |
| `HashTest` | 필드 하나 꺼내기/바꾸기, 전체 꺼내기 | 객체 필드 단위 저장 |
| `SetTest` | 중복 없음, `isMember`(SISMEMBER), 교집합 | 태그, 좋아요 |
| `SortedSetTest` | 점수 순 정렬, 점수 더하기, 등수(1등이 0) | 랭킹 |
| `SerializationTest` | JDK 직렬화는 키 이름까지 바뀜, `@class` 유무 차이 | |

헷갈렸던 것:
- **List 의 left/right**: 왼쪽 = 앞, 오른쪽 = 뒤. `rightPush` = 뒤에 붙이기.
- **INCR 이 안전한 이유**: Redis 가 "읽고 더하고 쓰기"를 한 번에 처리한다. 자바에서 get → +1 → set 으로 나누면 그 사이에 남이 끼어든다.
- **`getExpire` 결과**: 양수 = 그 초 뒤 사라짐 / `-1` = 키는 있는데 사라질 예정 없음 / `-2` = 키 없음.
- **`setIfAbsent` 가 `Boolean`(대문자)인 이유**: 파이프라인/트랜잭션 안에서는 결과가 나중에 와서 `null` 을 돌려준다. 그래서 `Boolean.TRUE.equals(...)` 로 감싸 안전하게 비교한다.

---

## 5. 캐시 설정 (`CacheConfig`)

### 큰 그림

RedisTemplate 은 우리가 직접 만들었지만, **캐시 관리자는 Boot 가 만든다.** 우리는 Boot 가 만들 때 참고할 규칙만 넘긴다.

```
Boot: "캐시 관리자 만들게"
  1. builder 생성 + 기본 규칙 넣기      ← redisCacheConfiguration() 빈
  2. 커스터마이저 실행                  ← cacheCustomizer() 빈 (라이터 교체, 트랜잭션, 캐시별 예외 규칙)
  3. builder.build() → RedisCacheManager
```

- `@EnableCaching`: 캐시 전원 스위치. `@Cacheable` 이 붙은 객체를 **대리인(프록시)** 으로 감싼다. 같은 클래스 안에서 자기 메서드를 부르면 대리인을 안 거쳐서 캐시가 안 먹는다.
- `RedisCacheConfiguration` 빈을 Boot 가 만들어 주지는 않는다. 없으면 yaml(`spring.cache.redis.*`)로 내부에서 만들지만, **값은 항상 JDK 직렬화**이고 지터도 못 넣는다. 그래서 직접 만들었다. 직접 만들면 yaml 의 `spring.cache.redis.*` 는 무시된다.

### 기본 규칙: Spring 기본값 vs 우리 설정

| 설정 | Spring 기본값 | 우리 설정 | 확인 테스트 |
|---|---|---|---|
| `entryTtl` | **만료 없음** | 60초 ±20% (지터) | `CacheBasicsTest` |
| `disableCachingNullValues` | null 저장 허용 | null 금지 (넣으면 **에러**) | `CacheBasicsTest` |
| `computePrefixWith` | `members::1` | `v1::members::1` | `CacheBasicsTest` |
| 키 직렬화 | 문자열 | 문자열 | |
| 값 직렬화 | **JDK** | JSON | `CacheBasicsTest` |

### 체이닝이 되는 두 가지 방식

| | `RedisCacheConfiguration` | `RedisCacheManagerBuilder` |
|---|---|---|
| 메서드가 돌려주는 것 | **새 복사본** (불변 객체) | **자기 자신** (`this`) |
| 원본 | 안 바뀜 | 바뀜 |
| 결과를 안 받으면 | 아무 일 없음 | 그래도 적용됨 |

```java
c.entryTtl(Duration.ofSeconds(60));       // 복사본을 만들고 버림 → c 는 그대로
c = c.entryTtl(Duration.ofSeconds(60));   // 이렇게 받아야 바뀜
```

그래서 `defaults.entryTtl(30초)` 는 `defaults` 를 바꾸지 않고, TTL 만 다른 복사본을 만든다.
null 금지가 걸린 `defaults` 에서 null 허용으로 되돌리는 메서드는 없어서, `penetrationNullable` 캐시는 `defaultCacheConfig()` 에서 새로 시작한다.

### 커스터마이저 (`RedisCacheManagerBuilderCustomizer`)

설정을 담는 객체가 아니라 **"builder 를 받으면 이렇게 고쳐라"는 할 일 목록**(메서드 하나짜리 인터페이스)이다. 설정은 builder 에 쌓인다.

- `.cacheWriter(...)`: Redis 에 실제로 명령을 보내는 일꾼 교체
- `.transactionAware()`: 트랜잭션 안의 캐시 저장/삭제를 커밋 후로 미룸
- `.withCacheConfiguration(이름, 규칙)`: 이 이름의 캐시만 다른 규칙. 목록에 없는 캐시는 기본 규칙.

```
기본 규칙: 60초±20%, null 금지, v1::, JSON
예외 목록: penetrationNullable → 10초 + null 허용
          stampedeNaive / stampedeSync → 30초
```

### `transactionAware` vs `immediateWrites` (서로 다른 질문)

| 설정 | 정하는 것 | 빠지면 |
|---|---|---|
| `transactionAware()` | **언제 보낼까?** 지금 vs 커밋 후 | 롤백해도 캐시가 이미 지워짐 → `TransactionTest` 롤백 테스트 항상 실패 |
| `immediateWrites()` | **보낸 뒤 기다릴까?** | DEL 을 보내고 안 기다림 → 바로 조회하면 아직 있음 → 결과가 들쭉날쭉 |

```
@Cacheable/evict → [TransactionAware 포장] → RedisCache → [라이터: immediateWrites] → Redis
                    언제 보낼지                          보낸 뒤 기다릴지
```

Spring Data Redis 4.1 의 라이터 기본값은 **비동기**(보내고 바로 돌아옴)다. 그래서 `immediateWrites()` 를 켰다.

### 지터 (`jitter()`)

- 같은 시각에 캐싱된 키들이 같은 시각에 만료되면 그 순간 요청이 DB 로 몰린다. 만료 시각을 48~72초 사이로 흩뿌린다.
- TTL 을 고정값이 아니라 **저장할 때마다 실행되는 함수**(`TtlFunction`)로 넘긴다.
- `ratio` 가 1 이상이면 TTL 이 0 이하가 될 수 있고, Spring 은 0 이하를 "만료 없음"으로 저장한다. 그래서 `Assert` 로 시작 시점에 막는다.

---

## 6. 캐시 함정 6가지

| # | 함정 | 대응 | 코드 | 테스트 |
|---|---|---|---|---|
| ① | TTL 없음 → 영원히 남음 | `entryTtl` 필수 | `CacheConfig` | `CacheBasicsTest` |
| ② | 스탬피드 → 만료 순간 DB 몰림 | 지터 + 락 | `StampedeService`, `RedisLock` | `StampedeTest`, `StampedeWithLockingWriterTest` |
| ③ | 관통 → 없는 데이터는 매번 DB | "없음"을 짧게 캐싱 | `PenetrationService` | `PenetrationTest` |
| ④ | 트랜잭션 → 롤백됐는데 캐시만 지워짐 | `transactionAware()` | `CacheConfig`, `TransactionalCacheService` | `TransactionTest` |
| ⑤ | 키 설계 → DTO 바꿔 배포하면 옛 값과 충돌 | 키에 버전 `v1::` | `CacheConfig` | `CacheBasicsTest` |
| ⑥ | 직렬화 → JDK 는 못 읽음 | JSON | `RedisConfig`, `CacheConfig` | `CacheBasicsTest` |

### ② 스탬피드: 세 가지 방식 비교

| 방식 | 20명 동시 요청 시 DB 횟수 | 비고 |
|---|---|---|
| `naive` 대책 없음 | 20 | |
| `synced` (`sync = true`) 락 꺼짐 | **20** | Redis 캐시는 sync 를 라이터에 맡긴다. 기본 라이터엔 락이 없어 효과 없음 |
| `synced` 락 켜짐 (`enableLocking`) | 1 | 단점 2개 (아래) |
| `locked` (`RedisLock`) | 1 | 키 단위 + TTL 있는 락 |

**Spring 락킹 라이터(`enableLocking`)의 단점**
- 락 키를 Spring 이 `캐시이름~lock` 규칙으로 알아서 만든다 → `stampedeSync~lock`. **키가 아니라 캐시 전체에 걸린다.** `hot` 을 로딩하는 동안 이미 캐시에 있는 `cold` 조회도 기다린다. (측정: 369ms vs RedisLock 2ms)
- 락 키에 **만료가 없다**(TTL -1). 로딩 중 서버가 죽으면 락이 영원히 남아 그 캐시 전체가 멈춘다. 쓰려면 `lockTimeout` 을 줘야 한다.

### `RedisLock` + `locked()` 흐름 (빵집 비유)

| 코드 | 빵집 |
|---|---|
| 캐시 `stampedeLocked::hot` | 진열대 |
| `slowLoad` (0.4초) | 주방에서 빵 굽기 = DB |
| 락 `lock::stampedeLocked::hot` | 주방 문의 "굽는 중" 팻말 |
| 토큰 (UUID) | 팻말에 적은 내 이름 |
| `LOCK_TTL` 5초 | 팻말이 5초 뒤 저절로 떨어짐 |

```
① 진열대 확인            있으면 끝 (대부분 여기서 끝)
② 팻말 걸기 (SET NX PX)  실패 → ③ 진열대를 20ms 마다 보며 최대 5초 기다림, 안 나오면 직접 굽기
④ 진열대 한 번 더 확인     앞사람이 이미 채우고 팻말까지 뗐을 수 있다
⑤ 굽기 → ⑥ 진열 → ⑦ 팻말 떼기 (finally, 내 이름일 때만 = Lua 스크립트)
```

- 자바 `synchronized` 는 서버 한 대 안에서만 통한다. Redis 는 모든 서버가 같이 보니까 서버가 여러 대여도 1명만 굽는다.
- 약점: 기다리는 쪽은 팻말이 아니라 진열대만 본다. 굽는 쪽이 5초 넘게 걸리거나 실패하면, 5초에 다 같이 직접 구워서 스탬피드가 다시 생긴다. (실무는 Redisson 의 락 자동 연장)
- `@Cacheable` 을 안 거치므로 `CacheConfig` 규칙(`v1::`, 지터, JSON)이 적용되지 않는다.

### ③ 관통

| 방식 | 999번(없음) 5번 조회 시 DB | 이유 |
|---|---|---|
| `strict` 예외 던지기 | 5 | 예외는 캐시에 안 남는다 |
| `nullable` null 캐싱 | 1 | "없음" 표시가 10초 동안 남는다 |

- 10초로 짧게 주는 이유: 999번이 곧 가입할 수 있다. "없음"을 오래 기억하면 가입해도 조회가 안 된다.
- "없음" 표시(`NullValue`)는 값 직렬화를 JSON 으로 정해도 **항상 JDK 방식**으로 저장된다. redis-cli 에서 `\xac\xed...NullValue` 로 보인다.
- null 금지 캐시에서 null 을 반환하면 **저장을 건너뛰는 게 아니라 에러**가 난다. 다른 방법: 예외 던지기, null 허용 캐시 따로 쓰기, `@Cacheable(unless = "#result == null")`.

### ④ 트랜잭션

```
@Transactional 메서드
   캐시 삭제 요청 → 바로 안 지움, "커밋되면 지우기" 메모
   ├ 커밋 → 그때 지움
   └ 롤백 → 메모 버림, 캐시 그대로
```

`@CacheEvict` 는 기본(`beforeInvocation = false`)이 "메서드가 예외 없이 끝났을 때만" 지운다. 예외로 끝나면 `transactionAware` 와 상관없이 아예 안 지운다. 그래서 데모는 `CacheManager` 로 직접 지운다.

### ⑤ 키 버전

`CACHE_VERSION = "v1"` → 모든 키가 `v1::캐시이름::id`. DTO 모양을 바꿔 배포할 때 `v2` 로 올리면, 새 코드는 `v2::` 만 보고 옛 `v1::` 키는 TTL 이 지나면 사라진다. 배포 중 옛 서버와 새 서버가 같이 떠 있어도 섞이지 않는다.

---

## 7. 이 세션에서 찾아서 고친 것

| 문제 | 원인 | 수정 |
|---|---|---|
| 트랜잭션 데모가 롤백 후 캐시가 남는 이유가 엉뚱했음 | `@CacheEvict` 가 예외로 끝나면 아예 안 지움 | `CacheManager` 로 직접 evict |
| 커밋 후 확인 결과가 true/false 로 흔들림 | 라이터 기본값이 비동기 | `immediateWrites()` |
| 로그 안내 키가 `members::1` | 실제 키는 `v1::members::1` | 주석 수정 |
| "null 반환하면 저장 안 한다" 주석 | 실제로는 에러 | 주석 수정 (`CacheConfig`, `PenetrationService`) |
| "`sync = true` 는 같은 JVM 안에서는 동작" 주석 | 이 버전 Redis 캐시엔 자체 락 없음 | `RedisLock` 주석 수정 |
| 지터 ratio ≥ 1 이면 영구 키 | TTL ≤ 0 은 만료 없음으로 저장 | `Assert` 추가 (커밋 98f5cef) |

---

## 8. redis-cli 자주 쓰는 명령

```
redis-cli                         접속
KEYS v1::*                        캐시 키 전부 (테스트용. 운영에선 금지, SCAN 사용)
GET v1::members::1                값 보기
TTL v1::members::1                남은 초 (-1 만료없음, -2 키없음)
TYPE key                          자료구조 종류
LRANGE key 0 -1                   List 전체
HGETALL key                       Hash 전체
SMEMBERS key                      Set 전체
ZRANGE key 0 -1 WITHSCORES        Sorted Set 전체 (점수 포함)
EXISTS stampedeSync~lock          Spring 캐시 락이 걸려 있나
MONITOR                           Redis 로 들어오는 명령 실시간 보기 (Ctrl+C 로 종료)
FLUSHDB                           현재 DB 전부 삭제 (주의)
```

---

## 9. 자주 헷갈린 것

- **CommandLineRunner 는 언제 도나?** 스프링이 빈을 전부 만든 **뒤에** 돈다. 그래서 그 안에서 repository 를 안심하고 쓴다.
- **캐시 이름 / 캐시 키 / Redis 키**: `stampedeSync` / `cold` / `v1::stampedeSync::cold`
- **`synced("cold")` 를 두 번 부르면?** 첫 번째는 캐시가 비어 있어서 로드 후 저장, 두 번째는 꺼내기만 한다. `@Cacheable` 은 "있으면 꺼내고 없으면 로드해서 저장"이다.
- **`CompletableFuture.supplyAsync(...)` / `.get()`**: 다른 스레드에 일을 맡기고 진동벨을 받음 / 진동벨이 울릴 때까지 기다렸다가 결과 받기. `get()` 을 안 부르면 그 스레드의 에러가 조용히 묻힌다.
- **테스트 상수 `락_키 = "stampedeSync~lock"`**: Redis 에 넣는 게 아니라, Spring 이 만드는 키 이름을 확인하려고 똑같이 적어둔 것.
