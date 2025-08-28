package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.models.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    int countByBusinessAndUsageTypeAndOccurredAtBetween(
            Business business,
            UsageEvent.UsageType usageType,
            LocalDateTime start,
            LocalDateTime end);

    int countByBusinessAndUsageTypeInAndOccurredAtBetween(
            Business business,
            List<UsageEvent.UsageType> usageTypes,
            LocalDateTime start,
            LocalDateTime end);

    List<UsageEvent> findByBusinessAndUsageTypeAndBillingPeriodStartAndBillingPeriodEndOrderByOccurredAtDesc(
            Business business,
            UsageEvent.UsageType usageType,
            LocalDateTime periodStart,
            LocalDateTime periodEnd);

    @Query("""
        SELECT 
            DATE_FORMAT(ue.occurredAt, '%Y-%m') as month,
            SUM(CASE WHEN ue.usageType = 'SMS_REVIEW_REQUEST_SENT' THEN 1 ELSE 0 END) as smsSent,
            SUM(CASE WHEN ue.usageType = 'EMAIL_REVIEW_REQUEST_SENT' THEN 1 ELSE 0 END) as emailSent
        FROM UsageEvent ue 
        WHERE ue.business.id = :businessId 
        AND ue.occurredAt BETWEEN :startDate AND :endDate
        GROUP BY DATE_FORMAT(ue.occurredAt, '%Y-%m')
        ORDER BY month DESC
        """)
    List<Object[]> findMonthlyUsageByBusiness(
            @Param("businessId") Long businessId,
            @Param("startDate") LocalDateTime startDate,
            @Param("endDate") LocalDateTime endDate);

    boolean existsByRequestId(String requestId);

    List<UsageEvent> findByBusinessAndOccurredAtBetweenOrderByOccurredAtDesc(
            Business business,
            LocalDateTime start,
            LocalDateTime end);
}

