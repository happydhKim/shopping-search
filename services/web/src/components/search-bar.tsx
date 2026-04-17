"use client";

import { Search, X } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { useSearchStore } from "@/store/search-store";
import { cn } from "@/lib/cn";
import { fetchSuggestions } from "@/lib/api";

const SEARCH_DEBOUNCE_MS = 300;
// 자동완성은 검색보다 훨씬 짧은 디바운스. 너무 짧으면 매 키입력마다 요청이 나간다.
const SUGGEST_DEBOUNCE_MS = 120;

export function SearchBar() {
  const query = useSearchStore((s) => s.query);
  const setQuery = useSearchStore((s) => s.setQuery);
  const pushRecent = useSearchStore((s) => s.pushRecent);

  const [draft, setDraft] = useState(query);
  // 자동완성 쿼리는 debouncedDraft로, 실제 검색 트리거는 query(store)로 분리.
  const [debouncedDraft, setDebouncedDraft] = useState(draft);
  const [open, setOpen] = useState(false);
  const [activeIdx, setActiveIdx] = useState(-1);
  const wrapperRef = useRef<HTMLDivElement>(null);

  // 검색 디바운스.
  useEffect(() => {
    const t = setTimeout(() => {
      setQuery(draft);
      if (draft.trim()) pushRecent(draft);
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [draft, setQuery, pushRecent]);

  // 자동완성 디바운스.
  useEffect(() => {
    const t = setTimeout(() => setDebouncedDraft(draft), SUGGEST_DEBOUNCE_MS);
    return () => clearTimeout(t);
  }, [draft]);

  // 외부 클릭 시 드롭다운 닫기.
  useEffect(() => {
    function onDocClick(e: MouseEvent) {
      if (!wrapperRef.current) return;
      if (!wrapperRef.current.contains(e.target as Node)) setOpen(false);
    }
    document.addEventListener("mousedown", onDocClick);
    return () => document.removeEventListener("mousedown", onDocClick);
  }, []);

  const trimmed = debouncedDraft.trim();
  const { data: suggestions = [] } = useQuery({
    queryKey: ["suggest", trimmed],
    queryFn: ({ signal }) => fetchSuggestions(trimmed, signal),
    enabled: open && trimmed.length >= 1,
    staleTime: 30_000,
  });

  const commit = (value: string) => {
    setDraft(value);
    setQuery(value);
    pushRecent(value);
    setOpen(false);
    setActiveIdx(-1);
  };

  const onKeyDown = (e: React.KeyboardEvent<HTMLInputElement>) => {
    if (!open || suggestions.length === 0) return;
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setActiveIdx((i) => Math.min(i + 1, suggestions.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setActiveIdx((i) => Math.max(i - 1, -1));
    } else if (e.key === "Enter" && activeIdx >= 0) {
      e.preventDefault();
      commit(suggestions[activeIdx].title);
    } else if (e.key === "Escape") {
      setOpen(false);
      setActiveIdx(-1);
    }
  };

  return (
    <div ref={wrapperRef} className="relative">
      <Search
        className="absolute left-4 top-1/2 -translate-y-1/2 text-neutral-400"
        size={20}
      />
      <input
        aria-label="상품 검색"
        autoFocus
        autoComplete="off"
        value={draft}
        onChange={(e) => {
          setDraft(e.target.value);
          setOpen(true);
          setActiveIdx(-1);
        }}
        onFocus={() => setOpen(true)}
        onKeyDown={onKeyDown}
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
          onClick={() => {
            setDraft("");
            setOpen(false);
          }}
          aria-label="지우기"
          className="absolute right-3 top-1/2 -translate-y-1/2 rounded-full p-1 text-neutral-400 hover:bg-neutral-100 hover:text-neutral-700 dark:hover:bg-neutral-800"
        >
          <X size={18} />
        </button>
      )}

      {open && suggestions.length > 0 && (
        <ul
          role="listbox"
          className={cn(
            "absolute left-0 right-0 top-full z-10 mt-2 max-h-80 overflow-auto",
            "rounded-2xl border border-neutral-200 bg-white py-1 shadow-lg",
            "dark:border-neutral-800 dark:bg-neutral-950",
          )}
        >
          {suggestions.map((s, i) => (
            <li key={s.product_id} role="option" aria-selected={i === activeIdx}>
              <button
                type="button"
                // mousedown으로 처리해야 input blur보다 먼저 실행되어 드롭다운이 닫히기 전에 commit.
                onMouseDown={(e) => {
                  e.preventDefault();
                  commit(s.title);
                }}
                onMouseEnter={() => setActiveIdx(i)}
                className={cn(
                  "flex w-full items-center gap-2 px-4 py-2.5 text-left text-sm",
                  "text-neutral-800 dark:text-neutral-200",
                  i === activeIdx
                    ? "bg-neutral-100 dark:bg-neutral-900"
                    : "hover:bg-neutral-50 dark:hover:bg-neutral-900/70",
                )}
              >
                <Search size={14} className="shrink-0 text-neutral-400" />
                <span className="truncate">{s.title}</span>
              </button>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
