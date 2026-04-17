export type ProductSource = {
  product_id: string;
  title: string;
  brand: string;
  brand_id: string;
  category_leaf: string;
  category_path: string[];
  tags: string[];
  price: number;
  original_price: number;
  discount_rate: number;
  currency: string;
  stock: number;
  in_stock: boolean;
  sales_count: number;
  view_count: number;
  review_count: number;
  review_score: number;
  image_url: string | null;
  seller_id: string;
  shipping_free: boolean;
  created_at: string;
  updated_at: string;
};

export type SearchHit = {
  id: string;
  score: number;
  source: ProductSource;
  highlights?: Record<string, string[]>;
};

export type CategoryFacet = { key: string; count: number };
export type PriceRangeFacet = {
  key: string;
  count: number;
  from?: number;
  to?: number;
};
export type Facets = {
  categories?: CategoryFacet[];
  price_ranges?: PriceRangeFacet[];
};

export type SearchResponse = {
  total: number;
  took_ms: number;
  page: number;
  size: number;
  hits: SearchHit[];
  facets?: Facets;
};

export type SearchFilters = {
  category?: string | null;
  priceMin?: number | null;
  priceMax?: number | null;
};

export async function searchProducts(
  q: string,
  page: number,
  size: number,
  filters?: SearchFilters,
  signal?: AbortSignal,
): Promise<SearchResponse> {
  const params = new URLSearchParams({
    q,
    page: String(page),
    size: String(size),
  });
  if (filters?.category) params.set("category", filters.category);
  if (filters?.priceMin != null) params.set("price_min", String(filters.priceMin));
  if (filters?.priceMax != null) params.set("price_max", String(filters.priceMax));
  const res = await fetch(`/api/search?${params.toString()}`, { signal });
  if (!res.ok) {
    const text = await res.text().catch(() => "");
    throw new Error(`search-api ${res.status}: ${text || res.statusText}`);
  }
  return (await res.json()) as SearchResponse;
}

export type Suggestion = {
  product_id: string;
  title: string;
};

export type SuggestResponse = {
  suggestions: Suggestion[];
  took_ms?: number;
};

export async function fetchSuggestions(
  q: string,
  signal?: AbortSignal,
): Promise<Suggestion[]> {
  const res = await fetch(
    `/api/suggest?q=${encodeURIComponent(q)}`,
    { signal },
  );
  if (!res.ok) return [];
  const body = (await res.json()) as SuggestResponse;
  return body.suggestions ?? [];
}
