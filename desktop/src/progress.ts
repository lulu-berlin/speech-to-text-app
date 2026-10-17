// The download screen's script (roadmap item 50, test item 3): it shows what the pinned model
// downloads are doing and offers a retry; `window.stt` (preload.cts) is its only channel to main.
// A classic script — no imports or exports — compiled to build/progress.js for progress.html.

const fileList = document.querySelector<HTMLUListElement>('#files');
const statusLine = document.querySelector<HTMLParagraphElement>('#status');
const errorLine = document.querySelector<HTMLParagraphElement>('#error');
const retryButton = document.querySelector<HTMLButtonElement>('#retry');
if (!fileList || !statusLine || !errorLine || !retryButton) {
  throw new Error('progress.html is missing its elements');
}

interface ProgressRow {
  percent: HTMLSpanElement;
  bar: HTMLProgressElement;
}
const rows: Record<string, ProgressRow> = {};

function rowFor(list: HTMLUListElement, file: string): ProgressRow {
  const existing = rows[file];
  if (existing) return existing;
  const item = document.createElement('li');
  const head = document.createElement('div');
  head.className = 'row';
  const name = document.createElement('span');
  name.textContent = file;
  const percent = document.createElement('span');
  const bar = document.createElement('progress');
  bar.max = 100;
  head.append(name, percent);
  item.append(head, bar);
  list.append(item);
  const row = { percent, bar };
  rows[file] = row;
  return row;
}

window.stt.onProgress(({ file, received, total }) => {
  // total 0 marks a file that was ready without a download: in the Nix store, or already in the
  // cache and only needing verification.
  const percent = total > 0 ? Math.round((received / total) * 100) : 100;
  const row = rowFor(fileList, file);
  row.percent.textContent = `${percent}%`;
  row.bar.value = percent;
});

window.stt.onError((message) => {
  statusLine.textContent = 'The speech analysis models could not be prepared.';
  errorLine.textContent = message;
  errorLine.hidden = false;
  retryButton.hidden = false;
});

retryButton.addEventListener('click', () => {
  statusLine.textContent = 'Preparing the speech analysis models…';
  errorLine.hidden = true;
  retryButton.hidden = true;
  window.stt.retry();
});
