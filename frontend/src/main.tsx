import { createRoot } from 'react-dom/client';
import { BrowserRouter } from 'react-router';
import { App } from './app/App';

const root = document.getElementById('root');
if (root === null) {
  throw new Error('missing #root element');
}
createRoot(root).render(
  <BrowserRouter>
    <App />
  </BrowserRouter>,
);
