import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { App } from './App';

describe('the app root', () => {
  it('renders the page title', () => {
    render(<App />);
    expect(screen.getByRole('heading', { name: 'Speech-to-Text' })).toBeInTheDocument();
  });
});
