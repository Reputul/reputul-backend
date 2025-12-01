-- ============================================================================
-- V23: Add Widget Configurations Table for Social Proof Widgets
-- ============================================================================
-- This migration adds support for embeddable social proof widgets that
-- businesses can add to their websites to showcase reviews and ratings.
-- ============================================================================

-- Create widget_configurations table
CREATE TABLE IF NOT EXISTS widget_configurations (
                                                     id BIGSERIAL PRIMARY KEY,
                                                     business_id BIGINT NOT NULL,
                                                     organization_id BIGINT NOT NULL,

    -- Widget Identity (public key used in embed code)
                                                     widget_key VARCHAR(64) UNIQUE NOT NULL,
                                                     widget_type VARCHAR(32) NOT NULL,  -- POPUP, BADGE, CAROUSEL, GRID
                                                     name VARCHAR(100),

    -- Display Settings
                                                     theme VARCHAR(20) DEFAULT 'light',           -- light, dark, auto
                                                     primary_color VARCHAR(7) DEFAULT '#3B82F6',  -- Hex color
                                                     accent_color VARCHAR(7) DEFAULT '#10B981',   -- Secondary color
                                                     background_color VARCHAR(7),                  -- Optional custom background
                                                     text_color VARCHAR(7),                        -- Optional custom text color
                                                     border_radius INTEGER DEFAULT 8,              -- Rounded corners (px)
                                                     font_family VARCHAR(100),                     -- Custom font
                                                     position VARCHAR(20) DEFAULT 'bottom-right',  -- Popup position

    -- Content Settings
                                                     show_rating BOOLEAN DEFAULT TRUE,
                                                     show_review_count BOOLEAN DEFAULT TRUE,
                                                     show_badge BOOLEAN DEFAULT TRUE,
                                                     show_business_name BOOLEAN DEFAULT TRUE,
                                                     show_reputul_branding BOOLEAN DEFAULT TRUE,
                                                     min_rating INTEGER DEFAULT 4,                 -- Only show reviews with this rating or higher
                                                     max_reviews INTEGER DEFAULT 12,               -- Max reviews to display
                                                     show_photos BOOLEAN DEFAULT TRUE,
                                                     show_reviewer_name BOOLEAN DEFAULT TRUE,
                                                     show_review_date BOOLEAN DEFAULT TRUE,
                                                     show_platform_source BOOLEAN DEFAULT TRUE,   -- Show Google/Facebook icon

    -- Popup Widget Specific Settings
                                                     popup_delay_seconds INTEGER DEFAULT 3,        -- Delay before first popup
                                                     popup_display_duration INTEGER DEFAULT 8,     -- How long each popup shows
                                                     popup_interval_seconds INTEGER DEFAULT 15,    -- Time between popups
                                                     popup_max_per_session INTEGER DEFAULT 5,      -- Max popups per visitor session
                                                     popup_enabled_mobile BOOLEAN DEFAULT TRUE,
                                                     popup_animation VARCHAR(20) DEFAULT 'slide',  -- slide, fade, bounce

    -- Carousel/Grid Widget Specific Settings
                                                     layout VARCHAR(20) DEFAULT 'grid',            -- grid, carousel, list, masonry
                                                     columns INTEGER DEFAULT 3,                    -- Number of columns for grid
                                                     auto_scroll BOOLEAN DEFAULT FALSE,
                                                     scroll_speed INTEGER DEFAULT 5,               -- Seconds per slide
                                                     show_navigation_arrows BOOLEAN DEFAULT TRUE,
                                                     show_pagination_dots BOOLEAN DEFAULT TRUE,
                                                     card_shadow BOOLEAN DEFAULT TRUE,

    -- Badge Widget Specific Settings
                                                     badge_style VARCHAR(20) DEFAULT 'standard',   -- standard, compact, minimal, full
                                                     badge_size VARCHAR(20) DEFAULT 'medium',      -- small, medium, large

    -- Analytics
                                                     total_impressions BIGINT DEFAULT 0,
                                                     total_clicks BIGINT DEFAULT 0,
                                                     last_impression_at TIMESTAMP WITH TIME ZONE,
                                                     last_click_at TIMESTAMP WITH TIME ZONE,

    -- Security & Status
                                                     is_active BOOLEAN DEFAULT TRUE,
                                                     allowed_domains TEXT,                         -- Comma-separated allowed domains (null = all)

    -- Timestamps
                                                     created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                                     updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    -- Foreign Keys
                                                     CONSTRAINT fk_widget_config_business FOREIGN KEY (business_id)
                                                         REFERENCES businesses(id) ON DELETE CASCADE,
                                                     CONSTRAINT fk_widget_config_organization FOREIGN KEY (organization_id)
                                                         REFERENCES organizations(id) ON DELETE CASCADE,

    -- Constraints
                                                     CONSTRAINT chk_widget_type CHECK (widget_type IN ('POPUP', 'BADGE', 'CAROUSEL', 'GRID')),
                                                     CONSTRAINT chk_theme CHECK (theme IN ('light', 'dark', 'auto')),
                                                     CONSTRAINT chk_position CHECK (position IN ('top-left', 'top-right', 'bottom-left', 'bottom-right')),
                                                     CONSTRAINT chk_min_rating CHECK (min_rating >= 1 AND min_rating <= 5),
                                                     CONSTRAINT chk_max_reviews CHECK (max_reviews >= 1 AND max_reviews <= 50),
                                                     CONSTRAINT chk_columns CHECK (columns >= 1 AND columns <= 6),
                                                     CONSTRAINT chk_layout CHECK (layout IN ('grid', 'carousel', 'list', 'masonry')),
                                                     CONSTRAINT chk_badge_style CHECK (badge_style IN ('standard', 'compact', 'minimal', 'full')),
                                                     CONSTRAINT chk_badge_size CHECK (badge_size IN ('small', 'medium', 'large')),
                                                     CONSTRAINT chk_popup_animation CHECK (popup_animation IN ('slide', 'fade', 'bounce', 'none'))
);

-- Indexes for performance
CREATE INDEX idx_widget_config_business_id ON widget_configurations(business_id);
CREATE INDEX idx_widget_config_organization_id ON widget_configurations(organization_id);
CREATE INDEX idx_widget_config_widget_key ON widget_configurations(widget_key);
CREATE INDEX idx_widget_config_active ON widget_configurations(is_active) WHERE is_active = TRUE;
CREATE INDEX idx_widget_config_type ON widget_configurations(widget_type);

-- Add trigger for updated_at
CREATE OR REPLACE FUNCTION update_widget_config_updated_at()
    RETURNS TRIGGER AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ language 'plpgsql';

CREATE TRIGGER widget_config_updated_at_trigger
    BEFORE UPDATE ON widget_configurations
    FOR EACH ROW
EXECUTE FUNCTION update_widget_config_updated_at();

-- Comments for documentation
COMMENT ON TABLE widget_configurations IS 'Stores configuration for embeddable social proof widgets';
COMMENT ON COLUMN widget_configurations.widget_key IS 'Public unique identifier used in embed codes';
COMMENT ON COLUMN widget_configurations.widget_type IS 'Type of widget: POPUP (floating notifications), BADGE (compact rating display), CAROUSEL (scrolling reviews), GRID (review grid/wall)';
COMMENT ON COLUMN widget_configurations.allowed_domains IS 'Comma-separated list of allowed domains for CORS, null means all domains allowed';
COMMENT ON COLUMN widget_configurations.min_rating IS 'Minimum star rating for reviews to be displayed (1-5)';