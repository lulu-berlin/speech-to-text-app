package stt.http

/**
 * The page an old bookmark, or a browser whose session cookie was cleared, reaches in app mode: it says how to open the
 * app, since only the tray and `stt-app` hold the per-install secret. Self-contained: no scripts, no external files.
 */
internal val BROWSER_REFUSAL_PAGE =
    """
    <!doctype html>
    <html lang="en">
      <head>
        <meta charset="utf-8" />
        <meta name="viewport" content="width=device-width, initial-scale=1" />
        <title>Open the app from the tray</title>
        <style>
          body { font: 16px/1.6 system-ui, sans-serif; margin: 3rem auto; max-width: 34rem; padding: 0 1.5rem; }
          h1 { font-size: 1.4rem; margin-bottom: 0.5rem; }
          p { margin: 0.75rem 0; }
          code { background: #8882; border-radius: 4px; padding: 0.1rem 0.35rem; }
          @media (prefers-color-scheme: dark) { body { background: #16181c; color: #e6e6e6; } }
        </style>
      </head>
      <body>
        <h1>Open the app from the tray</h1>
        <p>
          This page is not signed in. The app answers only the browser its tray opened: a bookmark, or a browser whose
          session was cleared, arrives here.
        </p>
        <p>Open it from the tray's menu, or run <code>stt-app open</code> in a terminal.</p>
        <p>The service is running and your files are safe; this page is only the way in.</p>
      </body>
    </html>
    """.trimIndent()
