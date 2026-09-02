package com.example.test.cache;

/** 캐시 이름을 상수로 모아둔다. 오타 하나로 "안 먹는 캐시"가 생기는 걸 막는 가장 싼 방법. */
public final class CacheNames {

	/** 실제 도메인 캐시 (MemberService) */
	public static final String MEMBERS = "members";

	/** ② 스탬피드 실습용 */
	public static final String STAMPEDE_NAIVE = "stampedeNaive";
	public static final String STAMPEDE_SYNC = "stampedeSync";

	/** ③ 관통 실습용 */
	public static final String PENETRATION_STRICT = "penetrationStrict";
	public static final String PENETRATION_NULLABLE = "penetrationNullable";

	/** ④ 트랜잭션 실습용 */
	public static final String TX_DEMO = "txDemo";

	private CacheNames() {
	}
}
