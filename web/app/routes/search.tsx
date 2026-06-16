import { Link } from "react-router"
import { searchCards, type CardSummary } from "~/api/client"
import { getLocale } from "~/lib/locale"
import { PageHeader } from "~/components/page-header"
import type { LoaderFunctionArgs } from "react-router"

export function meta({ data }: { data: { name: string; cards: CardSummary[] } | undefined }) {
  const name = data?.name
  return [{ title: name ? `"${name}" — Search | ptcgl.dev` : "Search | ptcgl.dev" }]
}

export async function loader({ request }: LoaderFunctionArgs) {
  const locale = getLocale(request)
  const params = new URL(request.url).searchParams
  const name = params.get("name") ?? ""
  const exact = params.get("exact") === "true"
  const cards = name.trim() ? await searchCards(name, locale, exact) : []
  return { locale, name, cards }
}

export default function Search({
  loaderData,
}: {
  loaderData: Awaited<ReturnType<typeof loader>>
}) {
  const { locale, name, cards } = loaderData

  return (
    <div className="min-h-screen bg-background">
      <PageHeader locale={locale}>
        <Link
          to="/"
          className="text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          ← Home
        </Link>
        <span className="text-muted-foreground text-sm">/</span>
        <span className="text-sm font-medium truncate">
          {name ? `"${name}"` : "Search"}
        </span>
      </PageHeader>
      <main className="container mx-auto px-4 py-8">
        {name && (
          <p className="text-sm text-muted-foreground mb-6">
            {cards.length === 0
              ? `No cards found for "${name}"`
              : `${cards.length} card${cards.length === 1 ? "" : "s"} matching "${name}"`}
          </p>
        )}
        <div className="grid grid-cols-3 sm:grid-cols-4 md:grid-cols-6 lg:grid-cols-8 xl:grid-cols-10 gap-2">
          {cards.map((c) => (
            <Link
              key={c.id}
              to={`/cards/${c.id}`}
              className="rounded-md overflow-hidden border bg-card hover:border-primary/40 hover:shadow-md transition-all"
            >
              {c.thumb ? (
                <img
                  src={c.thumb}
                  alt={c.name ?? c.id}
                  className="w-full aspect-[2/3] object-cover"
                  loading="lazy"
                />
              ) : (
                <div className="w-full aspect-[2/3] bg-muted flex items-center justify-center">
                  <span className="text-[10px] text-muted-foreground text-center px-1">
                    {c.position ?? c.number}
                  </span>
                </div>
              )}
              <div className="px-1.5 py-1 border-t">
                <p className="text-[10px] text-muted-foreground leading-tight">
                  {c.position ?? c.number}
                </p>
                <p className="text-[10px] font-medium truncate leading-tight">
                  {c.name ?? "—"}
                </p>
              </div>
            </Link>
          ))}
        </div>
      </main>
    </div>
  )
}
