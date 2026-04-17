"use client";

import { create } from "zustand";
import { persist } from "zustand/middleware";

type SearchState = {
  query: string;
  recent: string[];
  setQuery: (q: string) => void;
  pushRecent: (q: string) => void;
  clearRecent: () => void;
};

const MAX_RECENT = 8;

export const useSearchStore = create<SearchState>()(
  persist(
    (set) => ({
      query: "",
      recent: [],
      setQuery: (q) => set({ query: q }),
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
    }),
    {
      name: "shopping-search.recent",
      partialize: (s) => ({ recent: s.recent }),
    },
  ),
);
