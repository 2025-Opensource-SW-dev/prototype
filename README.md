# 실시간 선착순 이벤트 시스템
- 치킨 디도스, 네고왕 이벤트 참고

### 요구사항
- 선착순 100명에게 할인쿠폰을 제공하는 이벤트를 진행하고자한다.
- 선착훈 100명에게만 지급되어야 한다.
- 순간적으로 몰리는 트레픽을 버틸 수 있어야 한다.

### 쿠폰 발급 로직

```java
@Service
public class ApplyService {

    private final CouponRepository couponRepository;

    public ApplyService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    public void apply(Long userId){
        long count = couponRepository.count();

        if (count > 100) {
            return;
        }

        couponRepository.save(new Coupon(userId));
    }
}
```

단순한 쿠폰 발급 로직 요청마다 쿠폰을 발급해주고 100번 발급했다면 더이상 발급 하지 않는다

### 1. 기대값 100보다 더많은 쿠폰이 발급

![](https://velog.velcdn.com/images/kim138762/post/a8176eb7-5803-4972-959a-fd6621ab1756/image.png)


이유: race condition 발생 했기 때문 (2개 이상의 쓰레드가 공유데이터에 access 해서)

![](https://velog.velcdn.com/images/kim138762/post/2c950461-50b5-4f8b-a98e-6f50ae01c623/image.png)


- 99번째 쿠폰에서 2개이상의 쓰레드가 동시에 쿠폰 발급 요청해서 발급된 쿠폰수가 100개가 넘어감
- **발급된 쿠폰의 개수를 조회하는 로직과 실제 쿠폰을 발급하는 로직간의 시점 차이로 인해 발생**

### 쿠폰 발급시 동시성 문제 해결 (**redis의 incr 명령어)**

1. 자바의  **synchronized 한 번에 한 스레드만 접근하게 만들기**
    - **서버가 여러대인 경우 여전히 Race Condition이 발생**
2. 데이터베이스 레벨에서 **Database Lock으로 동시성 문제를 해결**
    - **쿠폰 발급 로직에서 쿠폰 개수를 조회하는 부분부터 Lock을 걸면 성능 저하 일어날수 있음**
3. **redis의 incr를 사용 ** (선택)
    - redis는 싱글 스레드이기에 발급 작업이 완료되기 전까지 다른 쿠폰 발급 하지 않음
    - incr 명령어는 빠른 성능(O(1))을 제공함

```java
@Service
public class ApplyService {

    private final CouponRepository couponRepository;

    public ApplyService(CouponRepository couponRepository) {
        this.couponRepository = couponRepository;
    }

    public void apply(Long userId){
        // redis incr key: value 1씩 증가
        // redis는 싱글 스레드
        // redis는 incr는 빠름

        Long count = couponCountRepository.increment();

        if (count > 100) {
            return;
        }

        couponRepository.save(new Coupon(userId));
    }
}
```

```java
@Repository
@RequiredArgsConstructor
public class CouponRepository{
	private final RedisTemplate<String, String> redisTemplate;

	public Long increment(){
		return redisTemplate.opsForValue().increment("coupon_count");
	}
}
```

![](https://velog.velcdn.com/images/kim138762/post/272b7e0f-6aa4-41fd-b4c1-93668eab7ec9/image.png)


실행한 결과 결과는 잘 나왔다

이 구조의 문제는 쿠폰 발급하는 개수가 많아질 수록 DB에 부하를 많이 준다

### 1분에 100개의 insert가 가능하다고 가정>

- 10:00 쿠폰 생성 10000개 요청
- 10:01 주문생성 요청
- 10:02 회원가입 요청

10:00에 요청된 쿠폰 생성때문에 다른 작업은 100분 후에 실행 될것이다, 그러면 timeout이 일어날 것이다

또한 DB의 과부화로 인하여 RDB의 cpu 사용량 과다로 서비스 지연과 오류가 발생할 수 있다

### 2. Kafka를 이용해서 문제 해결

컨피그 파일을 이용해서 kafkaTemplate 을 사용할 수 있도록 설정

```java
@Configuration
public class KafkaProducerConfig {
	@Bean
	public ProducerFactory<String, Long> producerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		// key serializer class config
		// value serializer class config
		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	public KafkaTemplate<String, Long> kafkaTemplate(){
		return new KafkaTemplate<>(producerFactory());
	}
}
```

ApplyService 의 apply 메서드를 kafkaTemplate 을 이용해서 produce 하는 형태로 변경

그리고 consumer 에서 처리하는 형태를 사용

```java
@Configuration
public class KafkaConsumerConfig {

	@Bean
	public ConsumerFactory<String, Long> consumerFactory() {
		Map<String, Object> config = new HashMap<>();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "localhost:9092");
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "group1");
		// key serializer class config
		// value serializer class config
		return new DefaultKafkaConsumerFactory<>(config);
	}

// ConcurrentKafkaListenerContainerFactory 사용
	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, Long> kafkaListenerContainerFactory(){
		ConcurrentKafkaListenerContainerFactory<String, Long> factory = new ConcurrentKafkaListenerContainerFactory();
		factory.setConsumerFactory(consumerFactory());
		return factory;
	}
}
```

이제 CouponCreatedConsumer 하나 구현하기 위해 @KafkaListener 를 붙여준 메서드를 하나 구현함.

**Kafka를 이용하여 쿠폰 발급시 지연 문제와 부하문제 해결할 수 있었다**

### 3. 선착순 이벤트는 1인당 1개의 쿠폰으로 제한

현재 로직은 한명이 여러개의 쿠폰을 가질수 있기에 1인당 발급 가능한 쿠폰 개수를 1개로 제한해본다.

이를 위해선 여러가지 방법이 있을 수 있는데,

1. database unique key
    - 한 명의 유저가 동일한 쿠폰을 발급받는 경우는 대응 불가
2. 범위로 락을 잡고 처음에 coupon 발급 여부를 확인 후 발급되었다면 return
    - 쿠폰을 백엔드에서 직접 발급하고, 전체를 lock 으로 잡는다면 가능은 하겠지만 성능 이슈 발생
3. Redis Set 을 사용 (선택)
    - Redis 에서도 set 자료구조를 사용하기 때문에 Set으로 중복 확인

### 완성된 실시간 선착순 이벤트 시스템 다이어그램
<img width="813" height="172" alt="image" src="https://github.com/user-attachments/assets/072df958-5cf8-45f6-a741-36de9fdfd0a7" />

