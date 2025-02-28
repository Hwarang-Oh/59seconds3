package com.ssafy.fiftyninesec.participation.service;

import com.ssafy.fiftyninesec.global.exception.CustomException;
import com.ssafy.fiftyninesec.participation.dto.ParticipationResponseDto;
import com.ssafy.fiftyninesec.participation.entity.Participation;
import com.ssafy.fiftyninesec.participation.repository.ParticipationRepository;
import com.ssafy.fiftyninesec.solution.entity.EventRoom;
import com.ssafy.fiftyninesec.solution.entity.Member;
import com.ssafy.fiftyninesec.solution.repository.EventRoomRepository;
import com.ssafy.fiftyninesec.solution.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

import org.springframework.transaction.annotation.Transactional;

import static com.ssafy.fiftyninesec.global.constants.RedisConstants.*;
import static com.ssafy.fiftyninesec.global.exception.ErrorCode.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ParticipationService {

    private static final long PARTICIPATION_QUEUE_RATE = 3000; // 5초

    private final AtomicLong rankingCounter = new AtomicLong(0);

    private final MemberRepository memberRepository;
    private final EventRoomRepository eventRoomRepository;
    private final ParticipationRepository participationRepository;

    private final RedissonClient redissonClient;
    private final SimpMessagingTemplate messagingTemplate;
    private final RedisTemplate<String, Object> redisTemplate;

    // 기존 참여자들을 조회
    @Transactional(readOnly = true)
    public List<ParticipationResponseDto> getParticipationsByRoomId(Long roomId) {
        List<Participation> participations = participationRepository.findByRoomIdOrderByRankingAsc(roomId)
                .orElseThrow(()-> new CustomException(PARTICIPATIONS_NOT_FOUND));

        return participations.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    // 새로운 참여자를 저장하고 WebSocket으로 알림 전송
    @Transactional
    public ParticipationResponseDto saveParticipation(Long roomId, Long memberId) {
        String lockKey = PARTICIPATION_LOCK_PREFIX + roomId;
        RLock lock = redissonClient.getLock(lockKey);

        try {
            boolean isLocked = lock.tryLock(LOCK_WAIT_TIME, LOCK_LEASE_TIME, TimeUnit.MILLISECONDS);
            if (!isLocked) {
                throw new CustomException(LOCK_ACQUISITION_FAILED);
            }

            // 유효성 검사
            Member member = memberRepository.findById(memberId)
                    .orElseThrow(() -> new CustomException(MEMBER_NOT_FOUND));
            EventRoom room = eventRoomRepository.findById(roomId)
                    .orElseThrow(() -> new CustomException(EVENT_NOT_FOUND));

            validateEventTiming(room);
            validateDuplicateParticipation(roomId, memberId);

            // 1. 랭킹 생성
            String rankingKey = RANKING_KEY_PREFIX + roomId;
            Long currentRanking = redisTemplate.opsForValue().increment(rankingKey);

            // 2. Participation 객체 생성 및 저장
            Participation participation = Participation.builder()
                    .room(room)
                    .member(member)
                    .joinedAt(LocalDateTime.now())
                    .ranking(currentRanking.intValue())
                    .isWinner(currentRanking <= room.getWinnerNum())
                    .build();

            Participation savedParticipation = participationRepository.save(participation);

            // Redis 큐에 참여 정보 저장
            String queueKey = PARTICIPATION_QUEUE_PREFIX + roomId;
            ParticipationResponseDto responseDto = convertToDto(savedParticipation);
            redisTemplate.opsForList().rightPush(queueKey, responseDto);

            // 자신의 랭킹보다 낮은 참여자 정보 가져오기
            List<Object> participants = redisTemplate.opsForList().range(queueKey, 0, -1);
            List<ParticipationResponseDto> lowerRankedParticipants = new ArrayList<>();

            for (Object obj : participants) {
                if (obj instanceof ParticipationResponseDto) {
                    ParticipationResponseDto dto = (ParticipationResponseDto) obj;
                    if (dto.getRanking() < currentRanking.intValue()) { // 내 랭킹보다 낮은 경우
                        lowerRankedParticipants.add(dto);
                    }
                }
            }

            messagingTemplate.convertAndSend("/result/sub/participations/" + roomId, lowerRankedParticipants);

            return responseDto;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CustomException(LOCK_INTERRUPTED);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Scheduled(fixedRate = PARTICIPATION_QUEUE_RATE)
    public void processParticipationQueue() {

        log.debug("processParticipationQueue: 매 {} ms", PARTICIPATION_QUEUE_RATE);
        Set<String> queueKeys = redisTemplate.keys(PARTICIPATION_QUEUE_PREFIX + "*");

        if (queueKeys == null || queueKeys.isEmpty()) {
            log.info("참여 큐가 없습니다.");
            return;
        }

        for (String queueKey : queueKeys) {
            Long roomId = Long.parseLong(queueKey.substring(queueKey.lastIndexOf(':') + 1));
            String lastProcessedKey = LAST_PROCESSED_ID_PREFIX + roomId; // 표시된 마지막 등수에 대한 키

            Integer lastProcessedRanking = (Integer) redisTemplate.opsForValue().get(lastProcessedKey);
            if (lastProcessedRanking == null) {
                lastProcessedRanking = 0;
            }

            List<ParticipationResponseDto> participations = new ArrayList<>();
            Object dto;

            //redis에 쌓인 정보 모두
            while ((dto = redisTemplate.opsForList().leftPop(queueKey)) != null) {
                try {
                    if (dto instanceof LinkedHashMap) {
                        ParticipationResponseDto participationDto = convertToParticipationDto((LinkedHashMap<String, Object>) dto);
                        if (participationDto.getRanking() > lastProcessedRanking) {
                            participations.add(participationDto);
                            log.info("Added participation: {}", participationDto);
                        }
                    }
                } catch (Exception e) {
                    log.error("Error converting participation data: {} - Error: {}", dto, e.getMessage());
                }
            }

            // 웹소켓으로 참여 정보 전송
            sendParticipations(roomId, participations, lastProcessedKey);
        }
    }

// -----------------------------------------------------------------------------------------------------

    private void sendParticipations(Long roomId, List<ParticipationResponseDto> participations, String lastProcessedKey) {
        if (!participations.isEmpty()) {
            participations.sort(Comparator.comparing(ParticipationResponseDto::getRanking));

            messagingTemplate.convertAndSend(
                    "/result/sub/participations/" + roomId,
                    participations
            );

            redisTemplate.opsForValue().set(
                    lastProcessedKey,
                    participations.get(participations.size() - 1).getRanking()
            );

            log.info("Sent {} participations for room {}", participations.size(), roomId);
        }
    }

    private void validateEventTiming(EventRoom room) {
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(room.getStartTime())) {
            throw new CustomException(EVENT_NOT_STARTED);
        }
    }

    private void validateDuplicateParticipation(Long roomId, Long memberId) {
        if (participationRepository.existsByRoomIdAndMemberId(roomId, memberId)) {
            throw new CustomException(ALREADY_PARTICIPATED);
        }
    }

    // rankingCounter 초기화를 위한 메서드 추가
    public void resetTestRanking() {
        rankingCounter.set(0);
    }

    // Redis에서 해당 rankingKey 삭제
    public void deleteEventRanking(Long roomId){
        String rankingKey = RANKING_KEY_PREFIX + roomId;
        redisTemplate.delete(rankingKey);
    }

// -----------------------------------------------------------------------------------------------------

    // 엔티티를 DTO로 변환하는 메서드
    private ParticipationResponseDto convertToDto(Participation participation) {
        return ParticipationResponseDto.builder()
                .eventId(participation.getRoom().getId())
                .memberId(participation.getMember().getId())
                .joinedAt(participation.getJoinedAt())
                .ranking(participation.getRanking())
                .isWinner(participation.getIsWinner())
                .winnerName(participation.getMember().getCreatorName())
                .build();
    }

    private ParticipationResponseDto convertToParticipationDto(LinkedHashMap<String, Object> map) {
        LocalDateTime joinedAt = convertToLocalDateTime(map.get("joinedAt"));

        return ParticipationResponseDto.builder()
                .eventId(((Number) map.get("eventId")).longValue())
                .memberId(((Number) map.get("memberId")).longValue())
                .joinedAt(joinedAt)
                .ranking(((Number) map.get("ranking")).intValue())
                .isWinner((Boolean) map.get("isWinner"))
                .winnerName((String) map.get("winnerName"))
                .build();
    }

    private LocalDateTime convertToLocalDateTime(Object joinedAt) {
        if (joinedAt instanceof ArrayList) {
            @SuppressWarnings("unchecked")
            ArrayList<Integer> dateList = (ArrayList<Integer>) joinedAt;
            return LocalDateTime.of(
                    dateList.get(0), // year
                    dateList.get(1), // month
                    dateList.get(2), // day
                    dateList.get(3), // hour
                    dateList.get(4), // minute
                    dateList.get(5), // second
                    dateList.get(6)  // nanosecond
            );
        } else {
            return LocalDateTime.parse((String) joinedAt);
        }
    }

// TEST -----------------------------------------------------------------------------------------------------

    // db 개입 없이 ws 테스트
    // 새로운 참여자를 저장하고 WebSocket으로 알림 전송
    @Transactional
    public ParticipationResponseDto saveParticipationTest(Long roomId, Long memberId) {
        try {
            // 1. 순위 생성 (AtomicLong 사용)
            long currentRanking = rankingCounter.incrementAndGet();

            // 2. 더미 데이터로 응답 DTO 생성
            ParticipationResponseDto responseDto = ParticipationResponseDto.builder()
                    .eventId(roomId)
                    .memberId(memberId)
                    .joinedAt(LocalDateTime.now())
                    .ranking((int) currentRanking)
                    .isWinner(currentRanking <= 100) // 테스트용 당첨자 수 100으로 고정
                    .winnerName("Tester_" + memberId) // 테스트용 이름
                    .build();

            // 3. WebSocket으로 실시간 알림 전송
            messagingTemplate.convertAndSend("/result/sub/participations/" + roomId, responseDto);

            log.info("Test participation processed - Room: {}, Member: {}, Ranking: {}",
                    roomId, memberId, currentRanking);

            return responseDto;

        } catch (Exception e) {
            log.error("Error in test participation: ", e);
            throw new CustomException(PARTICIPATION_FAILED);
        }
    }

}