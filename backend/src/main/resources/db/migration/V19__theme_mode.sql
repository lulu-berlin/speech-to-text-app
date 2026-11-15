-- Roadmap item 67 (the theme amendment, ADR 0068): the theme every frontend draws in — auto, light or dark. Auto
-- follows the OS's preference; it is the default, so a database from before this column reads auto.
ALTER TABLE settings ADD COLUMN theme_mode TEXT NOT NULL DEFAULT 'auto' CHECK (theme_mode IN ('auto', 'light', 'dark'));
