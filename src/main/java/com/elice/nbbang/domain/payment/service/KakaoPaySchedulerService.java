package com.elice.nbbang.domain.payment.service;

import static org.hibernate.query.sqm.tree.SqmNode.log;

import com.elice.nbbang.domain.payment.entity.Payment;
import com.elice.nbbang.domain.payment.entity.enums.PaymentStatus;
import com.elice.nbbang.domain.payment.repository.PaymentRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
@Transactional
public class KakaoPaySchedulerService {

    private final PaymentRepository paymentRepository;
    private final KakaoPayService kakaopayService;

    @Scheduled(cron = "0 0 0 * * ?")
    public void processRecurringPayments() {
        LocalDateTime today = LocalDateTime.now();

        List<Payment> paymentsDue = paymentRepository.findAllByStatusAndPaymentSubscribedAtBefore(
            PaymentStatus.SUBSCRIBED, today);

        for (Payment payment : paymentsDue) {
            try {
                kakaopayService.subscription(payment.getUser().getId(), payment.getOttId());
                paymentRepository.save(payment);
            } catch (Exception e) {
            }
        }
    }
}