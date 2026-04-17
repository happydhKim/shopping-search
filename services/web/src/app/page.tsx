import { SearchBar } from "@/components/search-bar";
import { ResultsList } from "@/components/results-list";
import { RecentSearches } from "@/components/recent-searches";

export default function Home() {
  return (
    <main className="mx-auto flex min-h-screen max-w-2xl flex-col px-5 pb-24 pt-10 sm:pt-16">
      <header className="mb-6">
        <h1 className="text-xl font-bold tracking-tight">shopping-search</h1>
        <p className="mt-1 text-sm text-neutral-500">
          Elasticsearch BM25 + function score · Kafka CDC 기반 near-real-time 색인
        </p>
      </header>

      <SearchBar />
      <RecentSearches />
      <ResultsList />
    </main>
  );
}
