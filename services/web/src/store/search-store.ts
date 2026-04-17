"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";

export type PriceRangeKey = "~10000" | "10000-30000" | "30000-100000" | "100000~";

type SearchState = {
  query: string;
  recent: string[];
  category: string | null;
  priceRange: PriceRangeKey | null;
  setQuery: (q: string) => void;
  pushRecent: (q: string) => void;
  clearRecent: () => void;
  setCategory: (c: string | null) => void;
  setPriceRange: (p: PriceRangeKey | null) => void;
  clearFilters: () => void;
};

const MAX_RECENT = 8;

export const useSearchStore = create<SearchState>()(
  persist(
    (set) => ({
      query: "",
      recent: [],
      category: null,
      priceRange: null,
      // 쿼리가 바뀌면 이전 쿼리에 특화된 필터는 의미가 없어지므로 함께 리셋.
      // ex) "나이키"에 걸어둔 카테고리=셔츠 필터가 "삼성" 검색에 끌려오면 혼란.
      setQuery: (q) => set({ query: q, category: null, priceRange: null }),
      pushRecent: (q) =>
        set((s) => {
          const trimmed = q.trim();
          if (!trimmed) return s;
          const next = [trimmed, ...s.recent.filter((x) => x !== trimmed)].slice(
            0,
            MAX_RECENT,
          );
          return { recent: next };
        }),
      clearRecent: () => set({ recent: [] }),
      setCategory: (c) => set({ category: c }),
      setPriceRange: (p) => set({ priceRange: p }),
      clearFilters: () => set({ category: null, priceRange: null }),
    }),
    {
      name: "shopping-search.recent",
      partialize: (s) => ({ recent: s.recent }),
    },
  ),
);
