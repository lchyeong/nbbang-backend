package com.elice.nbbang.domain.party.service;

import com.elice.nbbang.domain.notification.dto.SmsRequest;
import com.elice.nbbang.domain.notification.provider.NotificationEmailProvider;
import com.elice.nbbang.domain.notification.provider.NotificationSmsProvider;
import com.elice.nbbang.domain.ott.entity.Ott;
import com.elice.nbbang.domain.ott.exception.OttNotFoundException;
import com.elice.nbbang.domain.ott.repository.OttRepository;
import com.elice.nbbang.domain.party.entity.MatchingType;
import com.elice.nbbang.domain.party.entity.Party;
import com.elice.nbbang.domain.party.entity.PartyMember;
import com.elice.nbbang.domain.party.exception.PartyNotFoundException;
import com.elice.nbbang.domain.party.repository.PartyMemberRepository;
import com.elice.nbbang.domain.party.repository.PartyRepository;
import com.elice.nbbang.domain.party.service.dto.PartyMatchServiceRequest;
import com.elice.nbbang.domain.payment.dto.PaymentReserve;
import com.elice.nbbang.domain.payment.entity.Card;
import com.elice.nbbang.domain.payment.repository.CardRepository;
import com.elice.nbbang.domain.payment.service.AccountService;
import com.elice.nbbang.domain.payment.service.BootPayService;
import com.elice.nbbang.domain.payment.service.KakaoPayService;
import com.elice.nbbang.domain.payment.service.PaymentService;
import com.elice.nbbang.domain.user.entity.User;
import com.elice.nbbang.domain.user.repository.UserRepository;
import com.elice.nbbang.global.exception.ErrorCode;
import java.time.LocalDateTime;
import java.time.Period;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.elice.nbbang.global.util.UserUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import static com.elice.nbbang.domain.party.entity.PartyStatus.*;
import static com.elice.nbbang.domain.payment.entity.enums.PaymentType.*;


@Slf4j
@Transactional
@RequiredArgsConstructor
@Service
public class PartyMatchService {

    private final OttRepository ottRepository;
    private final UserRepository userRepository;
    private final PartyRepository partyRepository;
    private final CardRepository cardRepository;
    private final PartyMemberRepository partyMemberRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final KakaoPayService kakaoPayService;
    private final AccountService accountService;
    private final BootPayService bootPayService;
    private final UserUtil userUtil;
    private final PaymentService paymentService;
    private final NotificationSmsProvider notificationSmsProvider;
    private final NotificationEmailProvider notificationEmailProvider;

    /*
    * 많은 수의 사용자가 동시에 자동 매칭을 시켯을 때 동시성 문제가 없나?
    * 있다면 처리를 어떻게 해야할까?
    * */
    public boolean addPartyMatchingQueue(final PartyMatchServiceRequest request) {
        log.info("addPartyMatchingQueue : {}", "Redis 큐에 넣기");
        final Ott ott = ottRepository.findById(request.ottId())
                .orElseThrow(() -> new OttNotFoundException(ErrorCode.NOT_FOUND_OTT));

        final User user = getAuthenticatedUser();

        String requestString = createRequestValue(user.getId(), MatchingType.MATCHING, ott.getId());
        String duplicatedString = createDuplicateValue(user.getId(), ott.getId());

        String listKey = "waiting:" + ott.getId();
        String setKey = "waiting_set:" + ott.getId();

        if (isDuplicateMatching(setKey, duplicatedString)) {
            log.info("중복된 레디스");
            return false;
        }

        addPartyMatchingQueue(setKey, listKey, requestString, duplicatedString);
        log.info("레디스 큐 넣기 성공");
        return true;
    }

    /*
    * 이거를 비동기로 해야할 듯?
    * 동시성 문제 해결?
    * */
    @Async("threadPoolTaskExecutor")
    public CompletableFuture<Boolean> partyMatch(final Long userId, final MatchingType type, final Long ottId) throws Exception {
        final Ott ott = ottRepository.findById(ottId)
                .orElseThrow(() -> new OttNotFoundException(ErrorCode.NOT_FOUND_OTT));

        final int capacity = ott.getCapacity();

        Card card = cardRepository.findByUserId(userId)
                .orElseThrow(() -> new NoSuchElementException("조회된 카드가 없습니다."));

        final User user = userRepository.findById(userId)
                .orElseThrow(() -> new NoSuchElementException("조회된 유저가 없습니다."));

        final List<Party> partyByOtt = partyRepository.findAvailablePartyByOtt(ottId, AVAILABLE);

        Optional<Party> availableParty = partyByOtt.stream()
                .filter(party -> party.getPartyMembers().size() < ott.getCapacity() - 1)
                .findFirst();

        if (availableParty.isPresent()) {
            Party party = availableParty.get();
            if (type.equals(MatchingType.MATCHING)) {
                // 카드 결제 서비스 로직여기서 시도하고 결제가 완료되면 Party, PartyMember 관계 맺기
                if (card.getPaymentType().equals(CARD)) { // 카드 결제
                    PaymentReserve reserve = PaymentReserve.builder()
                        .billingKey(card.getBillingKey())
                        .ott(ott)
                        .user(user)
                        .paymentSubscribedAt(LocalDateTime.now())
                        .build();
                    log.info("reserve : {}", reserve);
                    try {
                        bootPayService.reservePayment(reserve);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                } else { // 카카오페이 결제
                    log.info("결제 시도");
                    kakaoPayService.subscription(userId, ottId);
                    log.info("결제 성공");

                }
                addPartyMemberToParty(party, ott, user, capacity);
                SmsRequest smsRequest = new SmsRequest(
                        user.getPhoneNumber(),
                        "[N/BBANG]\n" + ott.getName() + " 매칭이 완료되었습니다.\nMy 파티에서 공유계정을 확인하세요!"
                );
                notificationSmsProvider.sendSms(smsRequest);

            } else {
                // 원래 있는 PartyMember 에서 새로운 Party 를 부여하는 메서드
                PartyMember partyMember = partyMemberRepository.findPartyMemberByOttIdAndUserId(
                        ott.getId(),
                        user.getId()
                );
                int rematchingDay = getRematchingDay(partyMember);

                if (card.getPaymentType().equals(CARD)) {
                    bootPayService.updatePayment(userId, ottId, rematchingDay);
                } else {
                    paymentService.updatePaymentSubscribedAt(userId, ottId, rematchingDay);
                }

                partyMember.setParty(party);

                SmsRequest smsRequest = new SmsRequest(
                        user.getPhoneNumber(),
                        "[N/BBANG]\n" + ott.getName() + " 재매칭이 완료되었습니다.\nMy 파티에서 공유계정을 확인하세요!"
                );
                notificationSmsProvider.sendSms(smsRequest);

            }
        } else {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(true);

    }

    private int getRematchingDay(PartyMember partyMember) {
        Period period = Period.between(partyMember.getBreakUpDate().toLocalDate(), LocalDateTime.now().toLocalDate());
        return period.getDays();
    }

    @Transactional
    public void partyBreakup(final Long partyId) {
        User user = getAuthenticatedUser();

        Party party = partyRepository.findByPartyIdAndUserId(partyId, user.getId())
                .orElseThrow(() -> new PartyNotFoundException(ErrorCode.NOT_FOUND_PARTY));
        log.info("파티 해체 : {}", party.getOtt().getName());

        // 파티 멤버 삭제 및 대기 큐에 추가
        List<PartyMember> partyMembers = partyMemberRepository.findByPartyIdWithPartyAndUser(partyId);

        for (PartyMember member : partyMembers) {
            log.info("member breakUpDate : {}", member.getBreakUpDate());
            log.info("파티원 큐에 넣어주기 : {}", member.getUser().getNickname());
            member.addBreakUpDate(LocalDateTime.now());
            log.info("member newBreakUpDate : {}", member.getBreakUpDate());

            member.withdrawParty();
            addPartyPriorityQueue(party.getOtt().getId(), MatchingType.REMATCHING, member.getUser().getId());

            SmsRequest smsRequest = new SmsRequest(
                    member.getUser().getPhoneNumber(),
                    "[N/BBANG]\n" + member.getOtt().getName() + "파티가 해체되었습니다.\n재매칭 완료 시 문자로 알려드릴게요!"
            );
            notificationSmsProvider.sendSms(smsRequest);

        }
        log.info("파티장 부분 정산");
        accountService.calculatePartialSettlement(party);
        log.info("파티장 부분 정산 성공");

        partyRepository.delete(party);

        // 추가적인 로직 (예: 사용자에게 알림 보내기 등)

    }

    private void addPartyMemberToParty(final Party party, final Ott ott, final User user, final int capacity) {
        if (party.getPartyStatus().equals(AVAILABLE)) {
            PartyMember partyMember = PartyMember.of(user, party, ott, LocalDateTime.now());
            party.changeStatus(capacity);
            partyMemberRepository.save(partyMember);
        }
    }

    private void addPartyPriorityQueue(Long ottId, MatchingType type, Long userId) {
        redisTemplate.opsForSet().add("waiting_set:" + ottId, createDuplicateValue(userId, ottId));
        redisTemplate.opsForList().leftPush("waiting:" + ottId, createRequestValue(userId, type, ottId));
    }

    private void addPartyMatchingQueue(String setKey, String listKey, String requestString, String duplicatedString) {
        redisTemplate.opsForSet().add(setKey, duplicatedString);
        redisTemplate.opsForList().rightPush(listKey, requestString);
    }

    private boolean isDuplicateMatching(String setKey, String requestString) {
        return Boolean.TRUE.equals(redisTemplate.opsForSet().isMember(setKey, requestString));
    }

    private String createRequestValue(Long userId, MatchingType type, Long ottId) {
        return userId + "," + type + "," + ottId;
    }

    private String createDuplicateValue(Long userId, Long ottId) {
        return userId + "," + ottId;
    }

    private User getAuthenticatedUser() {
        final String email = userUtil.getAuthenticatedUserEmail();
        return userRepository.findByEmail(email);
    }

}
