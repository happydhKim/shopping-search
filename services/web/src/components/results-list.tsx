"use client";

import { keepPreviousData, useQuery } from "@tanstack/react-query";
import { Loader2, AlertTriangle, SearchX } from "lucide-react";
import { searchProducts, type SearchResponse } from "@/lib/api";
import { useSearchStore } from "@/store/search-store";
import { ProductCard } from "./product-card";
import { FacetPanel, priceRangeToBounds } from "./facet-panel";

const PAGE_SIZE = 20;

export function ResultsList() {
  const query = useSearchStore((s) => s.query);
  const category = useSearchStore((s) => s.category);
  const priceRange = useSearchStore((s) => s.priceRange);

  const { priceMin, priceMax } = priceRangeToBounds(priceRange);

  const { data, isFetching, isError, error } = useQuery<SearchResponse>({
    // 필터가 바뀌면 캐시 키가 바뀌어 새 요청이 나가도록 queryKey에 포함.
    queryKey: ["search", query, PAGE_SIZE, category, priceRange],
    queryFn: ({ signal }) =>
      searchProducts(
        query,
        0,
        PAGE_SIZE,
        { category, priceMin, priceMax },
        signal,
      ),
    enabled: query.trim().length > 0,
    placeholderData: keepPreviousData,
  });

  if (!query.trim()) {
    return null;
  }

  if (isError) {
    return (
      <div className="mt-6 flex items-start gap-3 rounded-xl border border-rose-200 bg-rose-50 p-4 text-sm text-rose-700 dark:border-rose-900 dark:bg-rose-950/40 dark:text-rose-300">
        <AlertTriangle className="mt-0.5 shrink-0" size={18} />
        <div>
          <p className="font-semibold">검색 API 호출 실패</p>
          <p className="mt-1 text-xs font-mono">
            {error instanceof Error ? error.message : String(error)}
          </p>
          <p className="mt-2 text-xs opacity-80">
            search-api가 :8084에서 기동 중인지 확인하세요.
          </p>
        </div>
      </div>
    );
  }

  if (!data) {
    return (
      <div className="mt-6 flex items-center justify-center gap-2 rounded-xl border border-dashed border-neutral-300 bg-neutral-50 p-8 text-sm text-neutral-500 dark:border-neutral-800 dark:bg-neutral-950/40">
        <Loader2 className="animate-spin" size={16} />
        검색 중…
      </div>
    );
  }

  return (
    <>
      <FacetPanel facets={data.facets} />

      {data.total === 0 ? (
        <div className="mt-6 flex flex-col items-center gap-2 rounded-xl border border-dashed border-neutral-300 p-12 text-center text-sm text-neutral-500 dark:border-neutral-800">
          <SearchX size={28} className="text-neutral-400" />
          <p>
            <span className="font-semibold">“{query}”</span>에 대한 상품이 없습니다.
          </p>
        </div>
      ) : (
        <div className="mt-4 space-y-3">
          <div className="flex items-center justify-between text-xs text-neutral-500">
            <p>
              총 <span className="font-semibold text-neutral-800 dark:text-neutral-200">{data.total.toLocaleString()}</span>건
              {isFetching && (
                <Loader2 className="ml-2 inline animate-spin" size={12} />
              )}
            </p>
            <p className="font-mono">took {data.took_ms}ms</p>
          </div>
          <ul className="space-y-3">
            {data.hits.map((h) => (
              <li key={h.id}>
                <ProductCard hit={h} />
              </li>
            ))}
          </ul>
        </div>
      )}
    </>
  );
}
