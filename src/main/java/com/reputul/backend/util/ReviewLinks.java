package com.reputul.backend.util;

import com.reputul.backend.models.Business;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Central helpers for constructing public review links from stored Business fields.
 * - Prefers Google "write a review" deep link when Place ID is present.
 * - Falls back to Facebook page /reviews path when configured.
 * - Provides Yelp (if stored).
 * - Includes Google search fallback helpers for UI use-cases.
 */
public final class ReviewLinks {

    private ReviewLinks() {}

    /** Google "write a review" deep link (null if no place ID). */
    public static String googleReviewUrl(Business b) {
        if (b == null) return null;
        String pid = b.getGooglePlaceId();
        if (pid == null || pid.isBlank()) return null;
        return "https://search.google.com/local/writereview?placeid=" + pid.trim();
    }

    /** Facebook Reviews tab link (null if not configured). */
    public static String facebookReviewUrl(Business b) {
        if (b == null) return null;
        String base = b.getFacebookPageUrl();
        if (base == null || base.isBlank()) return null;
        base = base.trim();
        return base.endsWith("/") ? base + "reviews" : base + "/reviews";
    }

    /** Yelp business page URL exactly as stored (null if not configured). */
    public static String yelpUrl(Business b) {
        if (b == null) return null;
        String url = b.getYelpPageUrl();
        if (url == null || url.isBlank()) return null;
        return url.trim();
    }

    /** Best public destination (Google preferred, else Facebook, else Yelp; null if none). */
    public static String bestPublicReviewUrl(Business b) {
        String g = googleReviewUrl(b);
        if (g != null) return g;
        String f = facebookReviewUrl(b);
        if (f != null) return f;
        return yelpUrl(b);
    }

    /** Google Maps search by business name + address (fallback for UIs). */
    public static String googleMapsSearch(Business b) {
        if (b == null) return null;
        String name = safe(b.getName());
        String addr = safe(b.getAddress());
        if (!name.isBlank() && !addr.isBlank()) {
            String q = url(name + " " + addr);
            return "https://www.google.com/maps/search/" + q;
        }
        if (!name.isBlank()) {
            String q = url(name + " reviews");
            return "https://www.google.com/search?q=" + q;
        }
        return null;
    }

    private static String safe(String s) { return s == null ? "" : s.trim(); }
    private static String url(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
