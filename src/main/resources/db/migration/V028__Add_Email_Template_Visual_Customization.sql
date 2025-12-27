-- V15__Add_Email_Template_Visual_Customization.sql
-- Migration to support visual email template customization with organization-level styling

-- Create email_template_style table for organization-level branding
CREATE TABLE email_template_styles (
                                       id BIGSERIAL PRIMARY KEY,
                                       organization_id BIGINT NOT NULL,

    -- Logo & Business Name
                                       logo_url VARCHAR(500),
                                       logo_size VARCHAR(20) DEFAULT 'SMALL', -- SMALL, MEDIUM, LARGE
                                       logo_position VARCHAR(20) DEFAULT 'LEFT', -- LEFT, CENTER, RIGHT
                                       show_business_name BOOLEAN DEFAULT true,
                                       business_name_position VARCHAR(20) DEFAULT 'CENTER', -- LEFT, CENTER, RIGHT

    -- Custom Images
                                       custom_image_url VARCHAR(500),
                                       show_custom_image BOOLEAN DEFAULT false,

    -- Text Alignment
                                       text_alignment VARCHAR(20) DEFAULT 'LEFT', -- LEFT, CENTER, RIGHT

    -- Button Settings
                                       button_text VARCHAR(100) DEFAULT 'Leave Feedback',
                                       button_alignment VARCHAR(20) DEFAULT 'CENTER', -- LEFT, CENTER, RIGHT
                                       button_style VARCHAR(20) DEFAULT 'ROUNDED', -- ROUNDED, PILL
                                       button_color VARCHAR(7) DEFAULT '#00D682', -- Hex color

    -- Background & Container
                                       background_color VARCHAR(7) DEFAULT '#F2F2F7', -- Hex color
                                       container_background_color VARCHAR(7) DEFAULT '#FFFFFF', -- Hex color
                                       container_corners VARCHAR(20) DEFAULT 'ROUNDED', -- ROUNDED, SHARP

    -- Additional Styling
                                       primary_color VARCHAR(7) DEFAULT '#00D682',
                                       secondary_color VARCHAR(7) DEFAULT '#333333',
                                       text_color VARCHAR(7) DEFAULT '#333333',

    -- Metadata
                                       created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
                                       updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                       CONSTRAINT fk_email_template_styles_organization
                                           FOREIGN KEY (organization_id)
                                               REFERENCES organizations(id)
                                               ON DELETE CASCADE,

                                       CONSTRAINT unique_style_per_organization
                                           UNIQUE (organization_id)
);

-- Add indexes for performance
CREATE INDEX idx_email_template_styles_organization_id ON email_template_styles(organization_id);

-- Add style_id column to email_templates table to reference the organization's style
ALTER TABLE email_templates
    ADD COLUMN style_id BIGINT,
    ADD CONSTRAINT fk_email_templates_style
        FOREIGN KEY (style_id)
            REFERENCES email_template_styles(id)
            ON DELETE SET NULL;

-- Add simplified template fields
ALTER TABLE email_templates
    ADD COLUMN simplified_mode BOOLEAN DEFAULT false,
    ADD COLUMN button_url_type VARCHAR(50) DEFAULT 'FEEDBACK_GATE', -- FEEDBACK_GATE, GOOGLE, FACEBOOK, YELP, PRIVATE
    ADD COLUMN show_multiple_platforms BOOLEAN DEFAULT false;

-- Create index on style_id for joins
CREATE INDEX idx_email_templates_style_id ON email_templates(style_id);

-- Create default email template styles for existing organizations
INSERT INTO email_template_styles (
    organization_id,
    logo_size,
    logo_position,
    show_business_name,
    business_name_position,
    text_alignment,
    button_text,
    button_alignment,
    button_style,
    button_color,
    background_color,
    container_background_color,
    container_corners,
    primary_color,
    secondary_color,
    text_color
)
SELECT
    id,
    'SMALL',
    'LEFT',
    true,
    'CENTER',
    'LEFT',
    'Leave Feedback',
    'CENTER',
    'ROUNDED',
    '#00D682',
    '#F2F2F7',
    '#FFFFFF',
    'ROUNDED',
    '#00D682',
    '#333333',
    '#333333'
FROM organizations
WHERE NOT EXISTS (
    SELECT 1 FROM email_template_styles WHERE organization_id = organizations.id
);

-- Update existing templates to use simplified mode and reference their org's style
UPDATE email_templates et
SET
    simplified_mode = true,
    style_id = (
        SELECT ets.id
        FROM email_template_styles ets
                 INNER JOIN users u ON u.organization_id = ets.organization_id
        WHERE u.id = et.user_id
        LIMIT 1
    ),
    button_url_type = 'FEEDBACK_GATE',
    show_multiple_platforms = false
WHERE et.style_id IS NULL;

-- Add comments for documentation
COMMENT ON TABLE email_template_styles IS 'Stores organization-level email template styling and branding settings';
COMMENT ON COLUMN email_template_styles.logo_url IS 'URL to uploaded logo image';
COMMENT ON COLUMN email_template_styles.logo_size IS 'Size of logo: SMALL, MEDIUM, or LARGE';
COMMENT ON COLUMN email_template_styles.button_color IS 'Primary CTA button color in hex format';
COMMENT ON COLUMN email_template_styles.container_corners IS 'Email container corner style: ROUNDED or SHARP';
COMMENT ON COLUMN email_templates.simplified_mode IS 'If true, uses simplified single-button template instead of multi-platform';
COMMENT ON COLUMN email_templates.button_url_type IS 'Type of URL the main button should link to';