"use client";

import { Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useSearchStore } from "@/store/search-store";
import { cn } from "@/lib/cn";

export function SearchBar() {
  const query = useSearchStore((s) => s.query);
  const setQuery = useSearchStore((s) => s.setQuery);
  const pushRecent = useSearchStore((s) => s.pushRecent);

  const [draft, setDraft] = useState(query);
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Debounce typing → query. 300ms feels responsive without hammering the API.
  useEffect(() => {
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => {
      setQuery(draft);
      if (draft.trim()) pushRecent(draft);
    }, 300);
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
  }, [draft, setQuery, pushRecent]);

  return (
    <div className="relative">
      <Search
        className="absolute left-4 top-1/2 -translate-y-1/2 text-neutral-400"
        size={20}
      />
      <input
        aria-label="상품 검색"
        autoFocus
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        placeholder="상품명, 브랜드, 카테고리로 검색"
        className={cn(
          "w-full rounded-2xl border border-neutral-200 bg-white py-3.5 pl-12 pr-12",
          "text-base outline-none placeholder:text-neutral-400",
          "focus:border-neutral-900 focus:ring-2 focus:ring-neutral-900/10",
          "dark:border-neutral-800 dark:bg-neutral-950 dark:focus:border-neutral-100",
        )}
      />
      {draft && (
        <button
          type="button"
          onClick={() => setDraft("")}
          aria-label="지우기"
          className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full p-1 text-neutral-400 hover:bg-neutral-100 hover:text-neutral-700 dark:hover:bg-neutral-800"
        >
          <X size={18} />
        </button>
      )}
    </div>
  );
}
