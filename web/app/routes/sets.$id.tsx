import { Link } from "react-router"
import { fetchSet, fetchSetCards, type Set as PokemonSet, type CardSummary } from "~/api/client"
import type { LoaderFunctionArgs } from "react-router"

export function meta({ data }: { data: { set: PokemonSet; cards: CardSummary[] } | undefined }) {
  return [{ title: `${data?.set?.name ?? "Set"} | PTCGL Mirror` }]
}

export async function loader({ params }: LoaderFunctionArgs) {
  const [set, cards] = await Promise.all([
    fetchSet(params.id!),
    fetchSetCards(params.id!),
  ])
  return { set, cards }
}

export default function SetDetail({
  loaderData,
}: {
  loaderData: Awaited<ReturnType<typeof loader>>
}) {
  const { set, cards } = loaderData

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b sticky top-0 bg-background/95 backdrop-blur-sm z-10">
        <div className="container mx-auto px-4 h-14 flex items-center gap-3">
          <Link
            to="/"
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            ← Home
          </Link>
          <span className="text-muted-foreground text-sm">/</span>
          <div className="flex items-center gap-2 min-w-0">
            {set.logo && (
              <img src={set.logo} alt="" className="h-6 object-contain flex-none" />
            )}
            <span className="font-medium text-foreground truncate text-sm">
              {set.name ?? set.id}
            </span>
            <span className="text-xs text-muted-foreground flex-none">({set.code})</span>
          </div>
        </div>
      </header>
      <main className="container mx-auto px-4 py-8">
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
