import type { Metadata } from 'next';
import Link from 'next/link';
import './globals.css';

export const metadata: Metadata = {
  title: 'Agentic Workflows Dashboard',
  description: 'Monitor and manage AI agent workflows',
};

function Navigation() {
  return (
    <nav className="bg-white border-b border-gray-200 px-6 py-4">
      <div className="flex items-center justify-between max-w-7xl mx-auto">
        <Link href="/" className="text-xl font-semibold text-gray-900">
          Agentic Workflows
        </Link>
        <div className="flex gap-6">
          <Link
            href="/"
            className="text-gray-600 hover:text-gray-900 font-medium transition-colors"
          >
            Workflows
          </Link>
          <Link
            href="/reviews"
            className="text-gray-600 hover:text-gray-900 font-medium transition-colors"
          >
            Reviews
          </Link>
          <Link
            href="/usage"
            className="text-gray-600 hover:text-gray-900 font-medium transition-colors"
          >
            Usage
          </Link>
        </div>
      </div>
    </nav>
  );
}

export default function RootLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  return (
    <html lang="en">
      <body>
        <Navigation />
        <main className="max-w-7xl mx-auto px-6 py-8">{children}</main>
      </body>
    </html>
  );
}
