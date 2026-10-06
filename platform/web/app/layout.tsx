import type { Metadata } from 'next';
import './globals.css';

export const metadata: Metadata = {
  title: 'LedgerBank',
  description: 'Banking, cards, bill pay, invoicing and books for startups.',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <head>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet" />
      </head>
      <body className="min-h-screen font-sans text-[14px]">{children}</body>
    </html>
  );
}
