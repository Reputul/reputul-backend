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
import org.springframework.transaction.annotation.Transactional;

import jakarta.annotation.PostConstruct;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for handling all Stripe integration functionality including:
 * - Checkout session creation
 * - Webhook event processing
 * - Subscription lifecycle management
 * - Customer management
 * - Usage tracking integration
 */
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

    @Value("${app.frontend.url}")
    private String frontendUrl;

    @Value("${billing.portal.return.url:#{null}}")
    private String billingPortalReturnUrl;

    @Value("${beta.promo.codes}")
    private String betaPromoCodes;

    private final SubscriptionRepository subscriptionRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;

    private Set<String> validPromoCodes;

    // Simple in-memory event tracking for idempotency (production should use Redis/DB)
    private final Map<String, Long> processedEvents = new ConcurrentHashMap<>();
    private static final long EVENT_EXPIRY_MS = 24 * 60 * 60 * 1000; // 24 hours

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

    /**
     * Create a Stripe checkout session for subscription signup
     */
    public String createCheckoutSession(User user, Subscription.PlanType planType, String promoCode) throws StripeException {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        if (planType == null) {
            throw new IllegalArgumentException("Plan type cannot be null");
        }

        log.info("Creating checkout session for user {} with plan {}", user.getId(), planType);

        // Get or create Stripe customer
        Customer stripeCustomer = findOrCreateCustomer(user);

        // Get the primary business for metadata
        Business primaryBusiness = businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                .orElse(null);

        // Get price ID for the plan
        String priceId = getPriceIdForPlan(planType);

        // Build metadata
        Map<String, String> metadata = new HashMap<>();
        metadata.put("userId", user.getId().toString());
        metadata.put("plan", planType.name());

        if (primaryBusiness != null) {
            metadata.put("primaryBusinessId", primaryBusiness.getId().toString());
        }

        // Add promo code metadata if provided and valid
        if (isValidPromoCode(promoCode)) {
            metadata.put("promoCode", promoCode.toUpperCase());
            metadata.put("promoKind", "BETA_3_FREE_THEN_50");
        }

        // Create checkout session with subscription + metered SMS
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

        // Add metadata to session as well
        for (Map.Entry<String, String> entry : metadata.entrySet()) {
            paramsBuilder.putMetadata(entry.getKey(), entry.getValue());
        }

        // Apply trial period if this is a new customer
        if (shouldApplyTrial(user)) {
            paramsBuilder.setSubscriptionData(
                    SessionCreateParams.SubscriptionData.builder()
                            .putAllMetadata(metadata)
                            .setTrialPeriodDays(14L) // 14-day trial
                            .build()
            );
        }

        com.stripe.model.checkout.Session session = com.stripe.model.checkout.Session.create(paramsBuilder.build());

        log.info("Created checkout session {} for user {}", session.getId(), user.getId());
        return session.getUrl();
    }

    /**
     * Create a billing portal session for subscription management
     */
    public String createBillingPortalSession(User user) throws StripeException {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }

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

        log.info("Created billing portal session for user {}", user.getId());
        return portalSession.getUrl();
    }

    /**
     * Create usage record for metered billing (SMS)
     */
    public void createUsageRecord(String subscriptionItemId, int quantity, String idempotencyKey) throws StripeException {
        if (subscriptionItemId == null || subscriptionItemId.trim().isEmpty()) {
            throw new IllegalArgumentException("Subscription item ID cannot be null or empty");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be positive");
        }
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()) {
            throw new IllegalArgumentException("Idempotency key cannot be null or empty");
        }

        UsageRecordCreateOnSubscriptionItemParams params = UsageRecordCreateOnSubscriptionItemParams.builder()
                .setQuantity((long) quantity)
                .setTimestamp(Instant.now().getEpochSecond())
                .setAction(UsageRecordCreateOnSubscriptionItemParams.Action.INCREMENT)
                .build();

        UsageRecord.createOnSubscriptionItem(subscriptionItemId, params,
                RequestOptions.builder().setIdempotencyKey(idempotencyKey).build());

        log.info("Created Stripe usage record: {} units for item {} (idempotency: {})",
                quantity, subscriptionItemId, idempotencyKey);
    }

    /**
     * Process checkout session completion - create local subscription
     */
    @Transactional
    public Subscription processCheckoutCompleted(com.stripe.model.checkout.Session stripeSession) throws StripeException {
        String userId = stripeSession.getMetadata().get("userId");
        String planName = stripeSession.getMetadata().get("plan");
        String promoCode = stripeSession.getMetadata().get("promoCode");
        String promoKind = stripeSession.getMetadata().get("promoKind");

        if (userId == null || planName == null) {
            throw new IllegalArgumentException("Missing required metadata in checkout session: " + stripeSession.getId());
        }

        log.info("Processing checkout completion for user {} with plan {}", userId, planName);

        // Get the user
        User user = userRepository.findById(Long.parseLong(userId))
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + userId));

        // Get the Stripe subscription
        com.stripe.model.Subscription stripeSubscription =
                com.stripe.model.Subscription.retrieve(stripeSession.getSubscription());

        // Find SMS subscription item
        String smsSubscriptionItemId = null;
        for (SubscriptionItem item : stripeSubscription.getItems().getData()) {
            if (stripePriceSmsMetered.equals(item.getPrice().getId())) {
                smsSubscriptionItemId = item.getId();
                break;
            }
        }

        // Get or create the primary business for this user
        Business primaryBusiness = businessRepository.findFirstByUserOrderByCreatedAtAsc(user)
                .orElseThrow(() -> new IllegalArgumentException("No business found for user: " + userId));

        // Check if subscription already exists (idempotency)
        Optional<Subscription> existingSubscription = subscriptionRepository
                .findByStripeSubscriptionId(stripeSubscription.getId());
        if (existingSubscription.isPresent()) {
            log.info("Subscription already exists for Stripe subscription {}, returning existing",
                    stripeSubscription.getId());
            return existingSubscription.get();
        }

        // Create local subscription record
        Subscription localSubscription = Subscription.builder()
                .business(primaryBusiness)
                .plan(Subscription.PlanType.valueOf(planName.toUpperCase()))
                .status(mapStripeStatus(stripeSubscription.getStatus()))
                .stripeCustomerId(stripeSubscription.getCustomer())
                .stripeSubscriptionId(stripeSubscription.getId())
                .smsSubscriptionItemId(smsSubscriptionItemId)
                .currentPeriodStart(OffsetDateTime.ofInstant(
                        Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodStart()),
                        ZoneOffset.UTC))
                .currentPeriodEnd(OffsetDateTime.ofInstant(
                        Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodEnd()),
                        ZoneOffset.UTC))
                .build();

        // Handle trial period if present
        if (stripeSubscription.getTrialEnd() != null) {
            localSubscription.setTrialStart(OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(stripeSubscription.getTrialStart()),
                    ZoneOffset.UTC));
            localSubscription.setTrialEnd(OffsetDateTime.ofInstant(
                    Instant.ofEpochSecond(stripeSubscription.getTrialEnd()),
                    ZoneOffset.UTC));
            // BACKWARD COMPATIBILITY: Set legacy trial boolean field
            localSubscription.setTrial(true);
        }

        // Handle promo code if present
        if (promoCode != null && promoKind != null) {
            localSubscription.setPromoCode(promoCode);
            try {
                localSubscription.setPromoKind(Subscription.PromoKind.valueOf(promoKind));
                localSubscription.setPromoPhase(1); // Start in first phase
                localSubscription.setPromoEndsAt(localSubscription.getCurrentPeriodEnd().plusMonths(3));
            } catch (IllegalArgumentException e) {
                log.warn("Invalid promo kind {}, skipping promo setup", promoKind);
            }
        }

        // BACKWARD COMPATIBILITY: Sync legacy fields for existing controllers
        localSubscription.syncLegacyFields();

        subscriptionRepository.save(localSubscription);
        log.info("Created local subscription {} for Stripe subscription {}",
                localSubscription.getId(), stripeSubscription.getId());

        return localSubscription;
    }

    /**
     * Update local subscription from Stripe subscription object (webhook handler)
     */
    @Transactional
    public void updateSubscriptionFromStripe(com.stripe.model.Subscription stripeSubscription) throws StripeException {
        Optional<Subscription> localSubscriptionOpt = subscriptionRepository
                .findByStripeSubscriptionId(stripeSubscription.getId());

        if (localSubscriptionOpt.isEmpty()) {
            log.warn("No local subscription found for Stripe subscription {}", stripeSubscription.getId());
            return;
        }

        Subscription localSubscription = localSubscriptionOpt.get();

        log.info("Updating local subscription {} from Stripe subscription {}",
                localSubscription.getId(), stripeSubscription.getId());

        // Update subscription details from Stripe
        localSubscription.setStatus(mapStripeStatus(stripeSubscription.getStatus()));
        localSubscription.setCurrentPeriodStart(
                OffsetDateTime.ofInstant(
                        Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodStart()),
                        ZoneOffset.UTC
                )
        );
        localSubscription.setCurrentPeriodEnd(
                OffsetDateTime.ofInstant(
                        Instant.ofEpochSecond(stripeSubscription.getCurrentPeriodEnd()),
                        ZoneOffset.UTC
                )
        );

        // Update trial info if present
        if (stripeSubscription.getTrialEnd() != null) {
            localSubscription.setTrialEnd(
                    OffsetDateTime.ofInstant(
                            Instant.ofEpochSecond(stripeSubscription.getTrialEnd()),
                            ZoneOffset.UTC
                    )
            );
            // BACKWARD COMPATIBILITY: Set legacy trial boolean field
            localSubscription.setTrial(true);
        } else {
            localSubscription.setTrialEnd(null);
            // BACKWARD COMPATIBILITY: Clear legacy trial boolean field
            localSubscription.setTrial(false);
        }

        // Update plan if it changed (plan upgrades/downgrades)
        updatePlanFromStripeItems(localSubscription, stripeSubscription);

        // BACKWARD COMPATIBILITY: Sync legacy fields for existing controllers
        localSubscription.syncLegacyFields();

        subscriptionRepository.save(localSubscription);
        log.info("Updated local subscription {} from Stripe webhook", localSubscription.getId());
    }

    /**
     * Cancel subscription locally when Stripe subscription is deleted
     */
    @Transactional
    public void cancelSubscriptionLocally(String stripeSubscriptionId) {
        Optional<Subscription> localSubscriptionOpt = subscriptionRepository
                .findByStripeSubscriptionId(stripeSubscriptionId);

        if (localSubscriptionOpt.isEmpty()) {
            log.warn("No local subscription found for Stripe subscription {}", stripeSubscriptionId);
            return;
        }

        Subscription localSubscription = localSubscriptionOpt.get();
        localSubscription.setStatus(Subscription.SubscriptionStatus.CANCELED);

        // BACKWARD COMPATIBILITY: Clear trial status when cancelled
        localSubscription.setTrial(false);
        localSubscription.syncLegacyFields();

        subscriptionRepository.save(localSubscription);
        log.info("Cancelled local subscription {} for Stripe subscription {}",
                localSubscription.getId(), stripeSubscriptionId);
    }

    /**
     * Handle successful payment webhook
     */
    @Transactional
    public void handleSuccessfulPayment(com.stripe.model.Invoice invoice) throws StripeException {
        String subscriptionId = invoice.getSubscription();

        Optional<Subscription> localSubscriptionOpt = subscriptionRepository
                .findByStripeSubscriptionId(subscriptionId);

        if (localSubscriptionOpt.isEmpty()) {
            log.warn("No local subscription found for invoice payment: {}", invoice.getId());
            return;
        }

        Subscription localSubscription = localSubscriptionOpt.get();

        log.info("Processing successful payment for subscription {} (invoice: {})",
                localSubscription.getId(), invoice.getId());

        // If subscription was past due, reactivate it
        if (localSubscription.getStatus() == Subscription.SubscriptionStatus.PAST_DUE) {
            localSubscription.setStatus(Subscription.SubscriptionStatus.ACTIVE);
            localSubscription.syncLegacyFields(); // Sync after status change
            subscriptionRepository.save(localSubscription);
            log.info("Reactivated subscription {} after successful payment", localSubscription.getId());
        }

        // TODO: Send success notification email to user
        log.info("Processed successful payment for subscription {}", localSubscription.getId());
    }

    /**
     * Handle failed payment webhook
     */
    @Transactional
    public void handleFailedPayment(com.stripe.model.Invoice invoice) throws StripeException {
        String subscriptionId = invoice.getSubscription();

        Optional<Subscription> localSubscriptionOpt = subscriptionRepository
                .findByStripeSubscriptionId(subscriptionId);

        if (localSubscriptionOpt.isEmpty()) {
            log.warn("No local subscription found for failed invoice payment: {}", invoice.getId());
            return;
        }

        Subscription localSubscription = localSubscriptionOpt.get();

        log.warn("Processing failed payment for subscription {} (invoice: {})",
                localSubscription.getId(), invoice.getId());

        // Update subscription status to past due
        localSubscription.setStatus(Subscription.SubscriptionStatus.PAST_DUE);
        localSubscription.syncLegacyFields(); // Sync after status change
        subscriptionRepository.save(localSubscription);

        // TODO: Send failed payment notification email to user
        log.warn("Marked subscription {} as past due after failed payment", localSubscription.getId());
    }

    /**
     * Handle trial ending soon notification
     */
    @Transactional
    public void handleTrialWillEnd(com.stripe.model.Subscription stripeSubscription) {
        Optional<Subscription> localSubscriptionOpt = subscriptionRepository
                .findByStripeSubscriptionId(stripeSubscription.getId());

        if (localSubscriptionOpt.isEmpty()) {
            log.warn("No local subscription found for trial ending: {}", stripeSubscription.getId());
            return;
        }

        Subscription localSubscription = localSubscriptionOpt.get();

        log.info("Processing trial will end for subscription {}", localSubscription.getId());

        // TODO: Send trial ending notification email to user
        // TODO: Create notification record in database
    }

    /**
     * Find or create Stripe customer for a user
     */
    private Customer findOrCreateCustomer(User user) throws StripeException {
        // Try to find existing customer by email
        CustomerSearchParams searchParams = CustomerSearchParams.builder()
                .setQuery("email:'" + user.getEmail() + "'")
                .build();

        CustomerSearchResult result = Customer.search(searchParams);
        if (!result.getData().isEmpty()) {
            Customer existingCustomer = result.getData().get(0);
            log.debug("Found existing Stripe customer {} for user {}", existingCustomer.getId(), user.getId());
            return existingCustomer;
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
            paramsBuilder.putMetadata("businessName", primaryBusiness.getName());
        }

        Customer customer = Customer.create(paramsBuilder.build());
        log.info("Created Stripe customer {} for user {}", customer.getId(), user.getId());

        return customer;
    }

    /**
     * Get Stripe price ID for a plan type
     */
    private String getPriceIdForPlan(Subscription.PlanType planType) {
        return switch (planType) {
            case SOLO -> stripePriceSolo;
            case PRO -> stripePricePro;
            case GROWTH -> stripePriceGrowth;
        };
    }

    /**
     * Map Stripe subscription status to local enum
     */
    private Subscription.SubscriptionStatus mapStripeStatus(String stripeStatus) {
        return switch (stripeStatus) {
            case "trialing" -> Subscription.SubscriptionStatus.TRIALING;
            case "active" -> Subscription.SubscriptionStatus.ACTIVE;
            case "past_due" -> Subscription.SubscriptionStatus.PAST_DUE;
            case "canceled" -> Subscription.SubscriptionStatus.CANCELED;
            case "incomplete" -> Subscription.SubscriptionStatus.INCOMPLETE;
            case "incomplete_expired" -> Subscription.SubscriptionStatus.INCOMPLETE_EXPIRED;
            case "unpaid" -> Subscription.SubscriptionStatus.UNPAID;
            default -> {
                log.warn("Unknown Stripe status: {}, defaulting to INACTIVE", stripeStatus);
                yield Subscription.SubscriptionStatus.INACTIVE;
            }
        };
    }

    /**
     * Update local subscription plan based on Stripe subscription items
     */
    private void updatePlanFromStripeItems(Subscription localSubscription, com.stripe.model.Subscription stripeSubscription) {
        for (SubscriptionItem item : stripeSubscription.getItems().getData()) {
            String priceId = item.getPrice().getId();

            if (stripePriceSolo.equals(priceId)) {
                localSubscription.setPlan(Subscription.PlanType.SOLO);
                break;
            } else if (stripePricePro.equals(priceId)) {
                localSubscription.setPlan(Subscription.PlanType.PRO);
                break;
            } else if (stripePriceGrowth.equals(priceId)) {
                localSubscription.setPlan(Subscription.PlanType.GROWTH);
                break;
            }
        }
    }

    /**
     * Determine if user should get trial period
     */
    private boolean shouldApplyTrial(User user) {
        // Check if user has had a trial before
        Optional<Subscription> existingSubscription = subscriptionRepository.findByBusinessUserId(user.getId());
        return existingSubscription.isEmpty(); // Only new customers get trials
    }

    /**
     * Validate promo code
     */
    public boolean isValidPromoCode(String promoCode) {
        if (promoCode == null || promoCode.trim().isEmpty()) {
            return false;
        }
        return validPromoCodes.contains(promoCode.trim().toUpperCase());
    }

    /**
     * Construct webhook event with signature verification
     */
    public Event constructWebhookEvent(String payload, String sigHeader) throws StripeException {
        return Webhook.constructEvent(payload, sigHeader, webhookSecret);
    }

    /**
     * Check if event has already been processed (basic idempotency)
     */
    public boolean isEventProcessed(String eventId) {
        // Clean up expired events
        cleanupExpiredEvents();

        return processedEvents.containsKey(eventId);
    }

    /**
     * Mark event as processed
     */
    public void markEventAsProcessed(String eventId) {
        processedEvents.put(eventId, System.currentTimeMillis());
    }

    /**
     * Clean up expired event tracking entries
     */
    private void cleanupExpiredEvents() {
        long cutoff = System.currentTimeMillis() - EVENT_EXPIRY_MS;
        processedEvents.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * Get subscription details for frontend
     */
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
            if (subscription.getPromoKind() != null) {
                promoInfo.put("kind", subscription.getPromoKind().getDescription());
            }
            promoInfo.put("phase", subscription.getPromoPhase());
            promoInfo.put("endsAt", subscription.getPromoEndsAt());
            promoInfo.put("isInFreePhase", subscription.isInFreePromoPhase());
            promoInfo.put("isInDiscountPhase", subscription.isInDiscountPromoPhase());
            summary.put("promo", promoInfo);
        }

        return summary;
    }
}