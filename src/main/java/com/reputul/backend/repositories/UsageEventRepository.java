package com.reputul.backend.repositories;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.models.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    int countByBusinessAndUsageTypeAndOccurredAtBetween(
            Business business,
            UsageEvent.UsageType usageType,
            OffsetDateTime start,
            OffsetDateTime end);

    int countByBusinessAndUsageTypeInAndOccurredAtBetween(
            Business business,
            List<UsageEvent.UsageType> usageTypes,
            OffsetDateTime start,
            OffsetDateTime end);

    List<UsageEvent> findByBusinessAndUsageTypeAndBillingPeriodStartAndBillingPeriodEndOrderByOccurredAtDesc(
            Business business,
            UsageEvent.UsageType usageType,
            OffsetDateTime periodStart,
            OffsetDateTime periodEnd);

    @Query(value = """
    SELECT 
        TO_CHAR(ue.occurred_at, 'YYYY-MM') as month,
        SUM(CASE WHEN ue.usage_type = 'SMS_REVIEW_REQUEST_SENT' THEN 1 ELSE 0 END) as smsSent,
        SUM(CASE WHEN ue.usage_type = 'EMAIL_REVIEW_REQUEST_SENT' THEN 1 ELSE 0 END) as emailSent
    FROM usage_events ue 
    WHERE ue.business_id = :businessId 
    AND ue.occurred_at BETWEEN :startDate AND :endDate
    GROUP BY TO_CHAR(ue.occurred_at, 'YYYY-MM')
    ORDER BY TO_CHAR(ue.occurred_at, 'YYYY-MM') DESC
    """, nativeQuery = true)
    List<Object[]> findMonthlyUsageByBusiness(
            @Param("businessId") Long businessId,
            @Param("startDate") OffsetDateTime startDate,
            @Param("endDate") OffsetDateTime endDate);

    boolean existsByRequestId(String requestId);

    List<UsageEvent> findByBusinessAndOccurredAtBetweenOrderByOccurredAtDesc(
            Business business,
            OffsetDateTime start,
            OffsetDateTime end);
}

