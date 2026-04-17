"use client";

import { X } from "lucide-react";
import { cn } from "@/lib/cn";
import {
  useSearchStore,
  type PriceRangeKey,
} from "@/store/search-store";
import type { Facets, PriceRangeFacet } from "@/lib/api";

// 백엔드 range agg key와 동일하게 유지. 라벨은 UI에서만 변환.
const PRICE_LABELS: Record<PriceRangeKey, string> = {
  "~10000": "1만원 이하",
  "10000-30000": "1~3만원",
  "30000-100000": "3~10만원",
  "100000~": "10만원 이상",
};

const PRICE_ORDER: PriceRangeKey[] = [
  "~10000",
  "10000-30000",
  "30000-100000",
  "100000~",
];

type Props = {
  facets: Facets | undefined;
};

export function FacetPanel({ facets }: Props) {
  const category = useSearchStore((s) => s.category);
  const priceRange = useSearchStore((s) => s.priceRange);
  const setCategory = useSearchStore((s) => s.setCategory);
  const setPriceRange = useSearchStore((s) => s.setPriceRange);
  const clearFilters = useSearchStore((s) => s.clearFilters);

  // post_filter 구조라 category가 선택돼 있어도 facets.categories에는 전체가 들어온다.
  // 선택된 카테고리가 facet 버킷에 없는 드문 케이스(인덱스 갱신 사이 차이)에도 UI는 보여야 하므로,
  // 선택값을 facet list에 없으면 합성 버킷으로 붙여준다.
  const categories = facets?.categories ?? [];
  const visibleCategories = category && !categories.find((c) => c.key === category)
    ? [...categories, { key: category, count: 0 }]
    : categories;

  const priceMap = new Map<string, PriceRangeFacet>();
  (facets?.price_ranges ?? []).forEach((p) => priceMap.set(p.key, p));

  const hasFilter = Boolean(category) || Boolean(priceRange);
  const hasAnyFacet = visibleCategories.length > 0 || priceMap.size > 0;
  if (!hasAnyFacet) return null;

  return (
    <section className="mt-4 space-y-3">
      {visibleCategories.length > 0 && (
        <div>
          <div className="mb-1.5 flex items-center justify-between">
            <h3 className="text-xs font-semibold text-neutral-500">카테고리</h3>
            {hasFilter && (
              <button
                type="button"
                onClick={clearFilters}
                className="flex items-center gap-1 text-xs text-neutral-500 hover:text-neutral-800 dark:hover:text-neutral-200"
              >
                <X size={12} /> 필터 초기화
              </button>
            )}
          </div>
          <div className="flex flex-wrap gap-1.5">
            {visibleCategories.map((c) => {
              const active = c.key === category;
              return (
                <button
                  key={c.key}
                  type="button"
                  onClick={() => setCategory(active ? null : c.key)}
                  className={cn(
                    "rounded-full border px-3 py-1 text-xs transition",
                    active
                      ? "border-neutral-900 bg-neutral-900 text-white dark:border-neutral-100 dark:bg-neutral-100 dark:text-neutral-900"
                      : "border-neutral-200 bg-white text-neutral-700 hover:border-neutral-400 dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-300",
                  )}
                >
                  {c.key}
                  <span className={cn(
                    "ml-1.5 text-[10px]",
                    active ? "text-white/70 dark:text-neutral-600" : "text-neutral-400",
                  )}>
                    {c.count.toLocaleString()}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      )}

      {priceMap.size > 0 && (
        <div>
          <h3 className="mb-1.5 text-xs font-semibold text-neutral-500">가격</h3>
          <div className="flex flex-wrap gap-1.5">
            {PRICE_ORDER.map((key) => {
              const bucket = priceMap.get(key);
              if (!bucket) return null;
              const active = key === priceRange;
              return (
                <button
                  key={key}
                  type="button"
                  onClick={() => setPriceRange(active ? null : key)}
                  disabled={bucket.count === 0 && !active}
                  className={cn(
                    "rounded-full border px-3 py-1 text-xs transition",
                    "disabled:cursor-not-allowed disabled:opacity-40",
                    active
                      ? "border-neutral-900 bg-neutral-900 text-white dark:border-neutral-100 dark:bg-neutral-100 dark:text-neutral-900"
                      : "border-neutral-200 bg-white text-neutral-700 hover:border-neutral-400 dark:border-neutral-800 dark:bg-neutral-950 dark:text-neutral-300",
                  )}
                >
                  {PRICE_LABELS[key]}
                  <span className={cn(
                    "ml-1.5 text-[10px]",
                    active ? "text-white/70 dark:text-neutral-600" : "text-neutral-400",
                  )}>
                    {bucket.count.toLocaleString()}
                  </span>
                </button>
              );
            })}
          </div>
        </div>
      )}
    </section>
  );
}

// priceRange key → {min,max} — api.ts 호출용.
export function priceRangeToBounds(
  key: PriceRangeKey | null,
): { priceMin: number | null; priceMax: number | null } {
  switch (key) {
    case "~10000":
      return { priceMin: null, priceMax: 10000 };
    case "10000-30000":
      return { priceMin: 10000, priceMax: 30000 };
    case "30000-100000":
      return { priceMin: 30000, priceMax: 100000 };
    case "100000~":
      return { priceMin: 100000, priceMax: null };
    default:
      return { priceMin: null, priceMax: null };
  }
}
