"use client";

import { useState } from "react";
import Image from "next/image";
import { Package, Truck, Star } from "lucide-react";
import type { SearchHit } from "@/lib/api";
import { cn } from "@/lib/cn";

const krw = new Intl.NumberFormat("ko-KR");

export function ProductCard({ hit }: { hit: SearchHit }) {
  const p = hit.source;
  const discount = Math.round(p.discount_rate * 100);
  const [imgFailed, setImgFailed] = useState(false);
  const showImage = Boolean(p.image_url) && !imgFailed;

  return (
    <article className="group flex gap-4 rounded-2xl border border-neutral-200 bg-white p-4 transition-colors hover:border-neutral-400 dark:border-neutral-800 dark:bg-neutral-950 dark:hover:border-neutral-600">
      <div className="relative h-28 w-28 shrink-0 overflow-hidden rounded-xl bg-neutral-100 dark:bg-neutral-900">
        {showImage ? (
          <Image
            src={p.image_url as string}
            alt={p.title}
            fill
            sizes="112px"
            className="object-cover"
            unoptimized
            onError={() => setImgFailed(true)}
          />
        ) : (
          <div className="flex h-full w-full items-center justify-center text-neutral-400">
            <Package size={28} />
          </div>
        )}
      </div>

      <div className="flex min-w-0 flex-1 flex-col justify-between">
        <div>
          <div className="flex items-center gap-1.5 text-xs text-neutral-500">
            <span className="font-medium text-neutral-800 dark:text-neutral-200">
              {p.brand}
            </span>
            <span>·</span>
            <span>{p.category_leaf}</span>
          </div>
          <h3 className="mt-1 line-clamp-2 text-sm font-medium leading-snug text-neutral-900 dark:text-neutral-100">
            {p.title}
          </h3>
        </div>

        <div className="mt-2 flex items-end justify-between gap-2">
          <div>
            <div className="flex items-baseline gap-2">
              {discount > 0 && (
                <span className="text-sm font-semibold text-rose-600">
                  {discount}%
                </span>
              )}
              <span className="text-lg font-bold text-neutral-900 dark:text-neutral-100">
                {krw.format(p.price)}원
              </span>
            </div>
            {discount > 0 && (
              <span className="text-xs text-neutral-400 line-through">
                {krw.format(p.original_price)}원
              </span>
            )}
          </div>

          <div className="flex flex-col items-end gap-1 text-xs text-neutral-500">
            {p.review_count > 0 && (
              <span className="inline-flex items-center gap-0.5">
                <Star size={12} className="fill-amber-400 stroke-amber-400" />
                {p.review_score.toFixed(1)}
                <span className="text-neutral-400">
                  ({krw.format(p.review_count)})
                </span>
              </span>
            )}
            <div className="flex items-center gap-2">
              {p.shipping_free && (
                <span className="inline-flex items-center gap-0.5">
                  <Truck size={12} /> 무료배송
                </span>
              )}
              <span
                className={cn(
                  "rounded px-1.5 py-0.5 text-[10px] font-medium",
                  p.in_stock
                    ? "bg-emerald-50 text-emerald-700 dark:bg-emerald-950 dark:text-emerald-400"
                    : "bg-neutral-100 text-neutral-500 dark:bg-neutral-900",
                )}
              >
                {p.in_stock ? "재고 있음" : "품절"}
              </span>
            </div>
            <span className="font-mono text-[10px] text-neutral-400">
              score {hit.score.toFixed(3)}
            </span>
          </div>
        </div>
      </div>
    </article>
  );
}
