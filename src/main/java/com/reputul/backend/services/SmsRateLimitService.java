package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class SmsRateLimitService {

    @Value("${sms.rate_limit.daily_per_business:50}")
    private int dailyLimitPerBusiness;

    @Value("${sms.rate_limit.hourly_per_business:10}")
    private int hourlyLimitPerBusiness;

    @Value("${sms.quiet_hours.start:21}")
    private int quietHoursStart; // 9 PM

    @Value("${sms.quiet_hours.end:8}")
    private int quietHoursEnd; // 8 AM

    @Value("${sms.rate_limit.customer_daily:3}")
    private int customerDailyLimit;

    // In-memory rate limiting (consider Redis for production)
    private final ConcurrentHashMap<String, BusinessRateLimit> businessLimits = new ConcurrentHashMap<>();

    /**
     * Check if business can send SMS right now
     */
    public SmsRateLimitResult canSendSms(Business business, String customerPhone) {
        // Check quiet hours first
        if (isQuietHours()) {
            return SmsRateLimitResult.denied("SMS sending is restricted during quiet hours (9 PM - 8 AM)");
        }

        String businessKey = "business_" + business.getId();
        BusinessRateLimit limit = businessLimits.computeIfAbsent(businessKey, k -> new BusinessRateLimit());

        // Reset counters if needed
        limit.resetIfNeeded();

        // Check business daily limit
        if (limit.dailyCount.get() >= dailyLimitPerBusiness) {
            return SmsRateLimitResult.denied("Business daily SMS limit reached (" + dailyLimitPerBusiness + ")");
        }

        // Check business hourly limit
        if (limit.hourlyCount.get() >= hourlyLimitPerBusiness) {
            return SmsRateLimitResult.denied("Business hourly SMS limit reached (" + hourlyLimitPerBusiness + ")");
        }

        return SmsRateLimitResult.allowed();
    }

    /**
     * Record SMS send for rate limiting
     */
    public void recordSmsUsage(Business business) {
        String businessKey = "business_" + business.getId();
        BusinessRateLimit limit = businessLimits.computeIfAbsent(businessKey, k -> new BusinessRateLimit());

        limit.resetIfNeeded();
        limit.dailyCount.incrementAndGet();
        limit.hourlyCount.incrementAndGet();

        log.debug("SMS usage recorded for business {}: daily={}, hourly={}",
                business.getId(), limit.dailyCount.get(), limit.hourlyCount.get());
    }

    /**
     * Check if current time is within quiet hours
     */
    public boolean isQuietHours() {
        LocalTime now = LocalTime.now();
        LocalTime quietStart = LocalTime.of(quietHoursStart, 0);
        LocalTime quietEnd = LocalTime.of(quietHoursEnd, 0);

        // Handle case where quiet hours span midnight
        if (quietStart.isAfter(quietEnd)) {
            return now.isAfter(quietStart) || now.isBefore(quietEnd);
        } else {
            return now.isAfter(quietStart) && now.isBefore(quietEnd);
        }
    }

    /**
     * Get remaining SMS allowance for business
     */
    public SmsAllowance getBusinessAllowance(Business business) {
        String businessKey = "business_" + business.getId();
        BusinessRateLimit limit = businessLimits.get(businessKey);

        if (limit == null) {
            return new SmsAllowance(dailyLimitPerBusiness, hourlyLimitPerBusiness);
        }

        limit.resetIfNeeded();

        return new SmsAllowance(
                Math.max(0, dailyLimitPerBusiness - limit.dailyCount.get()),
                Math.max(0, hourlyLimitPerBusiness - limit.hourlyCount.get())
        );
    }

    /**
     * Calculate next available send time if rate limited
     */
    public LocalDateTime getNextAvailableSendTime(Business business) {
        if (isQuietHours()) {
            // Return next morning at quiet hours end
            LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
            return tomorrow.with(LocalTime.of(quietHoursEnd, 0));
        }

        SmsAllowance allowance = getBusinessAllowance(business);

        if (allowance.dailyRemaining <= 0) {
            // Next day at quiet hours end
            LocalDateTime tomorrow = LocalDateTime.now().plusDays(1);
            return tomorrow.with(LocalTime.of(quietHoursEnd, 0));
        }

        if (allowance.hourlyRemaining <= 0) {
            // Next hour
            return LocalDateTime.now().plusHours(1).withMinute(0).withSecond(0);
        }

        return LocalDateTime.now(); // Can send now
    }

    /**
     * Reset all rate limits (for testing or admin purposes)
     */
    public void resetAllLimits() {
        businessLimits.clear();
        log.info("All SMS rate limits have been reset");
    }

    /**
     * Get rate limit configuration
     */
    public SmsRateLimitConfig getConfiguration() {
        return new SmsRateLimitConfig(
                dailyLimitPerBusiness,
                hourlyLimitPerBusiness,
                customerDailyLimit,
                quietHoursStart,
                quietHoursEnd
        );
    }

    // Helper classes
    private static class BusinessRateLimit {
        AtomicInteger dailyCount = new AtomicInteger(0);
        AtomicInteger hourlyCount = new AtomicInteger(0);
        LocalDate lastResetDate = LocalDate.now();
        LocalDateTime lastHourlyReset = LocalDateTime.now().withMinute(0).withSecond(0);

        void resetIfNeeded() {
            LocalDate today = LocalDate.now();
            LocalDateTime currentHour = LocalDateTime.now().withMinute(0).withSecond(0);

            // Reset daily counter
            if (!lastResetDate.equals(today)) {
                dailyCount.set(0);
                lastResetDate = today;
            }

            // Reset hourly counter
            if (lastHourlyReset.isBefore(currentHour)) {
                hourlyCount.set(0);
                lastHourlyReset = currentHour;
            }
        }
    }

    public static class SmsRateLimitResult {
        private final boolean allowed;
        private final String reason;

        private SmsRateLimitResult(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public static SmsRateLimitResult allowed() {
            return new SmsRateLimitResult(true, null);
        }

        public static SmsRateLimitResult denied(String reason) {
            return new SmsRateLimitResult(false, reason);
        }

        public boolean isAllowed() { return allowed; }
        public String getReason() { return reason; }
    }

    public static class SmsAllowance {
        private final int dailyRemaining;
        private final int hourlyRemaining;

        public SmsAllowance(int dailyRemaining, int hourlyRemaining) {
            this.dailyRemaining = dailyRemaining;
            this.hourlyRemaining = hourlyRemaining;
        }

        public int getDailyRemaining() { return dailyRemaining; }
        public int getHourlyRemaining() { return hourlyRemaining; }
        public boolean canSend() { return dailyRemaining > 0 && hourlyRemaining > 0; }
    }

    public static class SmsRateLimitConfig {
        private final int dailyLimitPerBusiness;
        private final int hourlyLimitPerBusiness;
        private final int customerDailyLimit;
        private final int quietHoursStart;
        private final int quietHoursEnd;

        public SmsRateLimitConfig(int dailyLimitPerBusiness, int hourlyLimitPerBusiness,
                                  int customerDailyLimit, int quietHoursStart, int quietHoursEnd) {
            this.dailyLimitPerBusiness = dailyLimitPerBusiness;
            this.hourlyLimitPerBusiness = hourlyLimitPerBusiness;
            this.customerDailyLimit = customerDailyLimit;
            this.quietHoursStart = quietHoursStart;
            this.quietHoursEnd = quietHoursEnd;
        }

        // Getters
        public int getDailyLimitPerBusiness() { return dailyLimitPerBusiness; }
        public int getHourlyLimitPerBusiness() { return hourlyLimitPerBusiness; }
        public int getCustomerDailyLimit() { return customerDailyLimit; }
        public int getQuietHoursStart() { return quietHoursStart; }
        public int getQuietHoursEnd() { return quietHoursEnd; }
    }
}