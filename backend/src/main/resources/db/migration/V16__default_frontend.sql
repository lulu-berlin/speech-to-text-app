-- Roadmap item 66: the default frontend the tray and `stt-app open` use when none is named. It is one user
-- preference shared by the browser, the window, the TUI and the tray, so it lives with the other settings.
-- Null means "no default chosen yet"; the id refers to a descriptor in the per-user frontend registry.
ALTER TABLE settings ADD COLUMN default_frontend TEXT;
