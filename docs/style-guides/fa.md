# Persian (fa) — style guide

Companion to `docs/glossary/fa.md` and `docs/reviews/fa.md`; the standard is the user decision of
2026-11-12 in `docs/specs/localization.md`.

- **Direction:** the document runs right to left (`direction: rtl` in `docs/api/ui-locales.json`);
  Latin placeholders, `CPU`, `GPU` and file names keep their own direction inside the RTL text, and
  the layout mirrors the interface, which the direction item covers.
- **Address:** action labels use the verbal noun or infinitive (`لغو`, `حذف`, `بستن`, `انتقال`,
  `بازگشت`, `نگه داشتن`), and running instructions use the formal second-person plural imperative
  (`نشانی را بررسی کنید`, `انتخاب کنید`). Persian verbs carry no grammatical gender, and no
  pronoun or title addresses the reader: each instruction reaches them through the verb alone
  (`می‌توانید`, `بروید`, `انتخاب کنید`), and no second-person pronoun appears.
- **Gender:** `گوینده` names the voice's source and `نوبت` one speaker's turn to speak (`گفتار` is
  the mass noun for speech); neither marks a gender, and no sentence states one.
- **Punctuation:** `،` for the comma, `؟` for the question and `؛` for the semicolon, as Persian
  writes them; `.` closes a sentence; `:` follows the English catalog; the ellipsis `…`; the em dash
  `—`; the guillemets `«…»` for quoted interface text. Labels keep the English form without a final
  stop (`قالب فایل پشتیبانی نمی‌شود`, `یافت نشد`).
- **Phases:** nouns after the label (`تقسیم صدا`, `رونویسی`, `تشخیص گوینده`); the outcome line is
  `{outcome} در حین {phase}`, with a position `{outcome} در حین {phase}، در {time}`.
- **Status badges:** `در صف`, `در حال اجرا`, `در حال مکث`, `تکمیل‌شده`, `ناموفق`, `لغوشده`; the
  history filter reuses `تکمیل‌شده`, `ناموفق` and `لغوشده`. `مکث` is pause (the queue and the
  player); `توقف` stays the stop action (`توقف بخش`, `کار متوقف می‌شود`).
- **Access keys:** the editor's buttons show the shortcut in parentheses — `ذخیره (<u>S</u>)` and
  `دور انداختن (<u>D</u>)` — because the check requires one underlined letter per button; no two
  share one.
- **Products and codes:** `Speech-to-Text`, file formats, `CPU`, `GPU`, `رشته‌ها` and the whisper
  language codes; `رابط کاربری`, never `frontend`. The UI locale's own names come from
  `docs/api/ui-locales.json` (`فارسی` for this one).
- **Plurals:** Persian has the `one` and `other` ICU categories, and both messages are written; `#`
  stays where the number belongs.
- **Dates, times, numbers:** formatted by `Intl` from `fa-IR`, never hard-coded; literal digits in
  validation text are Persian too (`۱ تا ۲۰`), and the percent sign of `{stage} {percent}%` sits in
  the message, as in the English catalog.
- **Layout:** Persian is close to English in length: `settings.intro` is 167 code points against
  136, then `transcript.reassign.body` (127 against 120) and `queue.fallback.detail` (112 against
  114).
