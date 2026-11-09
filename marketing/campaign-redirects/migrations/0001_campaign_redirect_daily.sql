-- This table contains public campaign labels and aggregate day counts only.
CREATE TABLE IF NOT EXISTS campaign_redirect_daily (
  day TEXT NOT NULL,
  campaign_token TEXT NOT NULL,
  destination TEXT NOT NULL CHECK (destination = 'app_store'),
  count INTEGER NOT NULL DEFAULT 0 CHECK (count >= 0),
  PRIMARY KEY (day, campaign_token, destination)
);
