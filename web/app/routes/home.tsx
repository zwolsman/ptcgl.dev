import { Link } from "react-router"
import { fetchSeries, fetchSets, type Set as PokemonSet } from "~/api/client"

export function meta() {
  return [
    { title: "ptcgl.dev" },
    { name: "description", content: "Browse Pokémon TCG Live card sets" },
  ]
}

export async function loader() {
  const [series, sets] = await Promise.all([fetchSeries(), fetchSets()])

  const setsBySeries = sets.reduce<Record<string, PokemonSet[]>>((acc, set) => {
    const key = set.series ?? "__other__"
    if (!acc[key]) acc[key] = []
    acc[key].push(set)
    return acc
  }, {})

  // Sort sets within each series by release date desc
  for (const key of Object.keys(setsBySeries)) {
    setsBySeries[key].sort((a, b) =>
      (b.releaseDate ?? "").localeCompare(a.releaseDate ?? ""),
    )
  }

  // Sort series by the max release date of their sets desc
  const sortedSeries = series.slice().sort((a, b) => {
    const maxA = setsBySeries[a.id]?.[0]?.releaseDate ?? ""
    const maxB = setsBySeries[b.id]?.[0]?.releaseDate ?? ""
    return maxB.localeCompare(maxA)
  })

  return { series: sortedSeries, setsBySeries }
}

export default function Home({
  loaderData,
}: {
  loaderData: Awaited<ReturnType<typeof loader>>
}) {
  const { series, setsBySeries } = loaderData

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b sticky top-0 bg-background/95 backdrop-blur-sm z-10">
        <div className="container mx-auto px-4 h-14 flex items-center">
          <span className="font-semibold text-foreground tracking-tight">ptcgl.dev</span>
        </div>
      </header>
      <main className="container mx-auto px-4 py-8 space-y-10">
        {series.map((s) => {
          const sets = setsBySeries[s.id] ?? []
          if (sets.length === 0) return null
          return (
            <section key={s.id}>
              <div className="flex items-baseline gap-3 mb-4">
                <h2 className="text-sm font-semibold text-foreground">{s.id}</h2>
                <span className="text-xs text-muted-foreground">{sets.length} sets</span>
              </div>
              <div className="flex flex-wrap gap-3">
                {sets.map((set) => (
                  <Link
                    key={set.id}
                    to={`/sets/${set.id}`}
                    className="flex-none w-44 rounded-lg border bg-card p-3 hover:border-primary/30 hover:shadow-sm transition-all"
                  >
                    {set.logo ? (
                      <img
                        src={set.logo}
                        alt={set.name ?? set.id}
                        className="h-8 w-full object-contain mb-3"
                      />
                    ) : (
                      <div className="h-8 flex items-center mb-3">
                        <span className="text-xs font-medium text-foreground truncate">
                          {set.name ?? set.id}
                        </span>
                      </div>
                    )}
                    {set.name && set.logo && (
                      <p className="text-xs font-medium text-foreground truncate mb-0.5">
                        {set.name}
                      </p>
                    )}
                    <div className="flex items-center gap-1.5 text-xs text-muted-foreground">
                      <span>{set.code}</span>
                      {set.releaseDate && (
                        <>
                          <span>·</span>
                          <span>{set.releaseDate.slice(0, 4)}</span>
                        </>
                      )}
                    </div>
                  </Link>
                ))}
              </div>
            </section>
          )
        })}
      </main>
    </div>
  )
}
