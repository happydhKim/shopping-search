"use client";

import { Clock, X } from "lucide-react";
import { useEffect, useState } from "react";
import { useSearchStore } from "@/store/search-store";

export function RecentSearches() {
  const recent = useSearchStore((s) => s.recent);
  const setQuery = useSearchStore((s) => s.setQuery);
  const clearRecent = useSearchStore((s) => s.clearRecent);
  const query = useSearchStore((s) => s.query);

  // Avoid hydration mismatch — zustand/persist reads localStorage on mount.
  const [mounted, setMounted] = useState(false);
  useEffect(() => setMounted(true), []);

  if (!mounted || query.trim() || recent.length === 0) return null;

  return (
    <div className="mt-6">
      <div className="flex items-center justify-between">
        <h2 className="flex items-center gap-1.5 text-xs font-semibold uppercase tracking-wide text-neutral-500">
          <Clock size={14} /> 최근 검색어
        </h2>
        <button
          type="button"
          onClick={clearRecent}
          className="text-xs text-neutral-400 hover:text-neutral-700 dark:hover:text-neutral-200"
        >
          전체 삭제
        </button>
      </div>
      <ul className="mt-3 flex flex-wrap gap-2">
        {recent.map((q) => (
          <li key={q}>
            <button
              type="button"
              onClick={() => setQuery(q)}
              className="inline-flex items-center gap-1 rounded-full border border-neutral-200 bg-white px-3 py-1.5 text-sm text-neutral-700 hover:border-neutral-400 dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-200"
            >
              {q}
              <X size={12} className="opacity-0" />
            </button>
          </li>
        ))}
      </ul>
    </div>
  );
}
