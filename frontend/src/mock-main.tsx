// Entry point for `pnpm dev:mock`: starts the MSW worker, then renders the app exactly as
// main.tsx does. Kept out of index.html, so `pnpm build` never includes MSW or the worker.
import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './app/App';
import { worker } from './mocks/browser';

async function main() {
  await worker.start({ onUnhandledRequest: 'error' });

  const root = document.getElementById('root');
  if (root === null) {
    throw new Error('missing #root element');
  }
  createRoot(root).render(
    <BrowserRouter>
      <App />
    </BrowserRouter>,
  );
}

void main();
