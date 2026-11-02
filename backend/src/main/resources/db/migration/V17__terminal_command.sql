-- Roadmap item 66, D10: the command line of the terminal the TUI opens in. One user preference, shared by every
-- client that launches the TUI, so it lives with the other settings. Null means "use the OS launcher".
ALTER TABLE settings ADD COLUMN terminal_command TEXT;
