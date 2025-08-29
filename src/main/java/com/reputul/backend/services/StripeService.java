package com.reputul.backend.services;

import com.reputul.backend.models.Business;
import com.reputul.backend.models.Subscription;
import com.reputul.backend.models.User;
import com.reputul.backend.repositories.BusinessRepository;
import com.reputul.backend.repositories.SubscriptionRepository;
import com.reputul.backend.repositories.UserRepository;
import com.stripe.Stripe;
import com.stripe.exception.StripeException;
import com.stripe.model.*;
import com.stripe.net.RequestOptions;
import com.stripe.net.Webhook;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;

@Service
@Slf4j
public class StripeService {

    @Value("${stripe.secret.key}")
    private String stripeSecretKey;

    @Value("${stripe.webhook.secret}")
    private String webhookSecret;

    @Value("${stripe.price.solo}")
    private String stripePriceSolo;

    @Value("${stripe.price.pro}")
    private String stripePricePro;

    @Value("${stripe.price.growth}")
    private String stripePriceGrowth;

    @Value("${stripe.price.sms.metered}")
    private String stripePriceSmsMetered;

    @Value("${stripe.coupon.100.off}")
    private String stripeCoupon100Off;

    @Value("${stripe.coupon.50.off}")
    private String stripeCoupon50Off;

    @Value("${frontend.url}")
    private String frontendUrl;

    @Value("${billing.portal.return.url:#{null}}")
    private String billingPortalReturnUrl;

    @Value("${beta.promo.codes}")
    private String betaPromoCodes;

    private final SubscriptionRepository subscriptionRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    private Set<String> validPromoCodes;

    public StripeService(SubscriptionRepository subscriptionRepository,
                         BusinessRepository businessRepository,
                         UserRepository userRepository) {
        this.subscriptionRepository = subscriptionRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
    }

    @PostConstruct
    public void init() {
        Stripe.apiKey = stripeSecretKey;

        // Parse promo codes
        validPromoCodes = new HashSet<>();
        if (betaPromoCodes != null && !betaPromoCodes.trim().isEmpty()) {
            String[] codes = betaPromoCodes.split(",");
            for (String code : codes) {
                validPromoCodes.add(code.trim().toUpperCase());
            }
        }

        log.info("StripeService initialized with {} valid promo codes", validPromoCodes.size());
    }

    public String createCheckoutSession(User user, Subscription.PlanType planType, String promoCode) throws StripeException {
        // Get or create Stripe customer
        Customer stripeCustomer = findOrCreateCustomer(user);

        // Get the primary business for metadata
        Business primaryBusiness = businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                .orElse(null);

        // Get price ID for the plan
        String priceId = getPriceIdForPlan(planType);

        // Create checkout session - Fixed with proper imports and method calls
        SessionCreateParams params = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(stripeCustomer.getId())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(stripePriceSmsMetered)
                        .build())
                .setSuccessUrl(frontendUrl + "/account/billing?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(frontendUrl + "/pricing")
                .putMetadata("userId", user.getId().toString())
                .putMetadata("plan", planType.name())
                .build();

        // Add primary business ID if available
        Map<String, String> metadata = new HashMap<>();
        metadata.put("userId", user.getId().toString());
        metadata.put("plan", planType.name());

        if (primaryBusiness != null) {
            metadata.put("primaryBusinessId", primaryBusiness.getId().toString());
        }

        // Add promo code metadata if provided
        if (isValidPromoCode(promoCode)) {
            metadata.put("promoCode", promoCode.toUpperCase());
            metadata.put("promoKind", "BETA_3_FREE_THEN_50");
        }

        // Rebuild params with metadata
        SessionCreateParams.Builder paramsBuilder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.SUBSCRIPTION)
                .setCustomer(stripeCustomer.getId())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(priceId)
                        .setQuantity(1L)
                        .build())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setPrice(stripePriceSmsMetered)
                        .build())
                .setSuccessUrl(frontendUrl + "/account/billing?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(frontendUrl + "/pricing")
                .setSubscriptionData(
                        SessionCreateParams.SubscriptionData.builder()
                                .putAllMetadata(metadata)
                                .build()
                );

        // Add all metadata to session as well
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            paramsBuilder.putMetadata(entry.getKey(), entry.getValue());
        }

        com.stripe.model.checkout.Session session = com.stripe.model.checkout.Session.create(paramsBuilder.build());

        return session.getUrl();
    }

    public String createBillingPortalSession(User user) throws StripeException {
        Customer stripeCustomer = findOrCreateCustomer(user);

        String returnUrl = billingPortalReturnUrl != null ?
                billingPortalReturnUrl : frontendUrl + "/account/billing";

        com.stripe.param.billingportal.SessionCreateParams params =
                com.stripe.param.billingportal.SessionCreateParams.builder()
                        .setCustomer(stripeCustomer.getId())
                        .setReturnUrl(returnUrl)
                        .build();

        com.stripe.model.billingportal.Session portalSession =
                com.stripe.model.billingportal.Session.create(params);
        return portalSession.getUrl();
    }

    public void createUsageRecord(String subscriptionItemId, int quantity, String idempotencyKey) throws StripeException {
        UsageRecordCreateOnSubscriptionItemParams params = UsageRecordCreateOnSubscriptionItemParams.builder()
                .setQuantity((long) quantity)
                .setTimestamp(Instant.now().getEpochSecond())
                .setAction(UsageRecordCreateOnSubscriptionItemParams.Action.INCREMENT)
                .build();

        UsageRecord.createOnSubscriptionItem(subscriptionItemId, params,
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());

        log.info("Created Stripe usage record: {} units for item {}", quantity, subscriptionItemId);
    }

    public Subscription processCheckoutCompleted(com.stripe.model.checkout.Session stripeSession) throws StripeException {
        String userId = stripeSession.getMetadata().get("userId");
        String planName = stripeSession.getMetadata().get("plan");
        String promoCode = stripeSession.getMetadata().get("promoCode");
        String promoKind = stripeSession.getMetadata().get("promoKind");

        if (userId == null || planName == null) {
            throw new IllegalArgumentException("Missing required metadata in checkout session");
        }

        // Get the Stripe subscription
        com.stripe.model.Subscription stripeSubscription = com.stripe.model.Subscription.retrieve(stripeSession.getSubscription());

        // Find SMS subscription item
        String smsSubscriptionItemId = null;
        for (SubscriptionItem item : stripeSubscription.getItems().getData()) {
            if (stripePriceSmsMetered.equals(item.getPrice().getId())) {
                smsSubscriptionItemId = item.getId();
                break;
            }
        }

        // Create or update subscription in our database
        Long userIdLong = Long.valueOf(userId);

        // Get the User object first
        User user = userRepository.findById(userIdLong)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userIdLong));

        Business primaryBusiness = businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                .orElse(null);

        Subscription subscription = subscriptionRepository.findByBusinessUserId(userIdLong)
                .orElse(new Subscription());

        subscription.setStripeCustomerId(stripeSession.getCustomer());
        subscription.setStripeSubscriptionId(stripeSubscription.getId());
        subscription.setSmsSubscriptionItemId(smsSubscriptionItemId);
        subscription.setPlan(Subscription.PlanType.valueOf(planName));
        subscription.setStatus(mapStripeStatus(stripeSubscription.getStatus()));
        subscription.setCurrentPeriodStart(OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodStart()), ZoneOffset.UTC));
        subscription.setCurrentPeriodEnd(OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodEnd()), ZoneOffset.UTC));

        if (stripeSubscription.getTrialStart() != null) {
            subscription.setTrialStart(OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(stripeSubscription.getTrialStart()), ZoneOffset.UTC));
        }
        if (stripeSubscription.getTrialEnd() != null) {
            subscription.setTrialEnd(OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(stripeSubscription.getTrialEnd()), ZoneOffset.UTC));
        }

        if (primaryBusiness != null) {
            subscription.setBusiness(primaryBusiness);
        }

        // Handle promo code if present
        if (promoCode != null && isValidPromoCode(promoCode)) {
            subscription.setPromoCode(promoCode);
            subscription.setPromoKind(Subscription.PromoKind.BETA_3_FREE_THEN_50);
            subscription.setPromoPhase(1); // Starting in free phase
            subscription.setPromoStartsAt(OffsetDateTime.now(ZoneOffset.UTC));

            // Create subscription schedule for multi-phase discount
            String scheduleId = createPromoSubscriptionSchedule(stripeSubscription.getId(), planName);
            subscription.setStripeScheduleId(scheduleId);

            // Calculate when phase 1 ends (3 billing cycles)
            OffsetDateTime phaseEndDate = subscription.getCurrentPeriodEnd().plusMonths(2); // 3 cycles total
            subscription.setPromoEndsAt(phaseEndDate);

            log.info("Applied promo code '{}' to subscription {}", promoCode, stripeSubscription.getId());
        }

        return subscriptionRepository.save(subscription);
    }

    private String createPromoSubscriptionSchedule(String subscriptionId, String planName) throws StripeException {
        String priceId = getPriceIdForPlan(Subscription.PlanType.valueOf(planName));

        SubscriptionScheduleCreateParams params = SubscriptionScheduleCreateParams.builder()
                .setFromSubscription(subscriptionId)
                .setStartDate(SubscriptionScheduleCreateParams.StartDate.NOW)
                .addPhase(
                        // Phase 1: 3 billing cycles at 100% off
                        SubscriptionScheduleCreateParams.Phase.builder()
                                .addItem(SubscriptionScheduleCreateParams.Phase.Item.builder()
                                        .setPrice(priceId)
                                        .setQuantity(1L)
                                        .build())
                                .addItem(SubscriptionScheduleCreateParams.Phase.Item.builder()
                                        .setPrice(stripePriceSmsMetered)
                                        .build())
                                .setIterations(3L)
                                .setCoupon(stripeCoupon100Off)
                                .build())
                .addPhase(
                        // Phase 2: Forever at 50% off
                        SubscriptionScheduleCreateParams.Phase.builder()
                                .addItem(SubscriptionScheduleCreateParams.Phase.Item.builder()
                                        .setPrice(priceId)
                                        .setQuantity(1L)
                                        .build())
                                .addItem(SubscriptionScheduleCreateParams.Phase.Item.builder()
                                        .setPrice(stripePriceSmsMetered)
                                        .build())
                                .setCoupon(stripeCoupon50Off)
                                .build())
                .build();

        SubscriptionSchedule schedule = SubscriptionSchedule.create(params);
        log.info("Created subscription schedule {} for promo subscription {}", schedule.getId(), subscriptionId);

        return schedule.getId();
    }

    public void updateSubscriptionFromStripe(com.stripe.model.Subscription stripeSubscription) throws StripeException {
        Subscription localSubscription = subscriptionRepository.findByStripeSubscriptionId(stripeSubscription.getId())
                .orElse(null);

        if (localSubscription == null) {
            log.warn("No local subscription found for Stripe subscription {}", stripeSubscription.getId());
            return;
        }

        localSubscription.setStatus(mapStripeStatus(stripeSubscription.getStatus()));
        localSubscription.setCurrentPeriodStart(OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodStart()), ZoneOffset.UTC));
        localSubscription.setCurrentPeriodEnd(OffsetDateTime.ofInstant(
                Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodEnd()), ZoneOffset.UTC));

        // Check if we moved to phase 2 of promo (50% off phase)
        if (localSubscription.hasPromo() && localSubscription.getPromoPhase() == 1) {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            if (localSubscription.getPromoEndsAt() != null && now.isAfter(localSubscription.getPromoEndsAt())) {
                localSubscription.setPromoPhase(2); // Now in 50% off phase
                log.info("Subscription {} moved to promo phase 2 (50% off)", localSubscription.getId());
            }
        }

        subscriptionRepository.save(localSubscription);
        log.info("Updated local subscription {} from Stripe webhook", localSubscription.getId());
    }

    private Customer findOrCreateCustomer(User user) throws StripeException {
        // Try to find existing customer
        CustomerSearchParams searchParams = CustomerSearchParams.builder()
                .setQuery("email:'" + user.getEmail() + "'")
                .build();

        CustomerSearchResult result = Customer.search(searchParams);
        if (!result.getData().isEmpty()) {
            return result.getData().get(0);
        }

        // Create new customer
        Business primaryBusiness = businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                .orElse(null);

        CustomerCreateParams.Builder paramsBuilder = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getName())
                .putMetadata("userId", user.getId().toString());

        if (primaryBusiness != null) {
            paramsBuilder.putMetadata("primaryBusinessId", primaryBusiness.getId().toString());
        }

        Customer customer = Customer.create(paramsBuilder.build());
        log.info("Created Stripe customer {} for user {}", customer.getId(), user.getId());

        return customer;
    }

    private String getPriceIdForPlan(Subscription.PlanType planType) {
        return switch (planType) {
            case SOLO -> stripePriceSolo;
            case PRO -> stripePricePro;
            case GROWTH -> stripePriceGrowth;
        };
    }

    private Subscription.SubscriptionStatus mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "trialing" -> Subscription.SubscriptionStatus.TRIALING;
            case "active" -> Subscription.SubscriptionStatus.ACTIVE;
            case "past_due" -> Subscription.SubscriptionStatus.PAST_DUE;
            case "canceled" -> Subscription.SubscriptionStatus.CANCELED;
            case "incomplete" -> Subscription.SubscriptionStatus.INCOMPLETE;
            case "incomplete_expired" -> Subscription.SubscriptionStatus.INCOMPLETE_EXPIRED;
            case "unpaid" -> Subscription.SubscriptionStatus.UNPAID;
            default -> Subscription.SubscriptionStatus.INACTIVE;
        };
    }

    public boolean isValidPromoCode(String promoCode) {
        if (promoCode == null || promoCode.trim().isEmpty()) {
            return false;
        }
        return validPromoCodes.contains(promoCode.trim().toUpperCase());
    }

    public Event constructWebhookEvent(String payload, String sigHeader) throws StripeException {
        return Webhook.constructEvent(payload, sigHeader, webhookSecret);
    }

    // Get subscription details for frontend
    public Map<String, Object> getSubscriptionSummary(User user) {
        Optional<Subscription> subscriptionOpt = subscriptionRepository.findByBusinessUserId(user.getId());

        Map<String, Object> summary = new HashMap<>();

        if (subscriptionOpt.isEmpty()) {
            summary.put("hasSubscription", false);
            summary.put("plan", "NONE");
            summary.put("status", "INACTIVE");
            return summary;
        }

        Subscription subscription = subscriptionOpt.get();

        summary.put("hasSubscription", true);
        summary.put("plan", subscription.getPlan().name());
        summary.put("status", subscription.getStatus().name());
        summary.put("currentPeriodStart", subscription.getCurrentPeriodStart());
        summary.put("currentPeriodEnd", subscription.getCurrentPeriodEnd());

        if (subscription.isTrialing()) {
            summary.put("trialEnd", subscription.getTrialEnd());
        }

        if (subscription.hasPromo()) {
            Map<String, Object> promoInfo = new HashMap<>();
            promoInfo.put("code", subscription.getPromoCode());
            promoInfo.put("kind", subscription.getPromoKind().getDescription());
            promoInfo.put("phase", subscription.getPromoPhase());
            promoInfo.put("endsAt", subscription.getPromoEndsAt());
            promoInfo.put("isInFreePhase", subscription.isInFreePromoPhase());
            promoInfo.put("isInDiscountPhase", subscription.isInDiscountPromoPhase());
            summary.put("promo", promoInfo);
        }

        return summary;
    }
}