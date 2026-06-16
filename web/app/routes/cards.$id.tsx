import { Link } from "react-router"
import { fetchCard, type Card } from "~/api/client"
import { Badge } from "~/components/ui/badge"
import type { LoaderFunctionArgs } from "react-router"

export function meta({ data }: { data: Card | undefined }) {
  return [{ title: `${data?.name ?? "Card"} | PTCGL Mirror` }]
}

export async function loader({ params }: LoaderFunctionArgs) {
  return await fetchCard(params.id!)
}

export default function CardDetail({
  loaderData,
}: {
  loaderData: Awaited<ReturnType<typeof loader>>
}) {
  const card = loaderData
  const image = card.assets.hires ?? card.assets.thumb

  return (
    <div className="min-h-screen bg-background">
      <header className="border-b sticky top-0 bg-background/95 backdrop-blur-sm z-10">
        <div className="container mx-auto px-4 h-14 flex items-center gap-3">
          <Link
            to={`/sets/${card.setId}`}
            className="text-sm text-muted-foreground hover:text-foreground transition-colors"
          >
            ← Back to Set
          </Link>
        </div>
      </header>
      <main className="container mx-auto px-4 py-8">
        <div className="flex flex-col md:flex-row gap-10 max-w-3xl">
          {/* Card image */}
          <div className="flex-none w-full max-w-[260px] mx-auto md:mx-0">
            {image ? (
              <img
                src={image}
                alt={card.name ?? card.id}
                className="w-full rounded-2xl shadow-lg"
              />
            ) : (
              <div className="w-full aspect-[2/3] bg-muted rounded-2xl flex items-center justify-center">
                <span className="text-muted-foreground text-sm">No image</span>
              </div>
            )}
          </div>

          {/* Card details */}
          <div className="flex-1 space-y-5 min-w-0">
            <div>
              <h1 className="text-2xl font-bold text-foreground">{card.name ?? card.id}</h1>
              <p className="text-sm text-muted-foreground mt-0.5">
                #{card.position ?? card.number}
              </p>
            </div>

            {/* Tags */}
            <div className="flex flex-wrap gap-1.5">
              {card.category && <Badge variant="secondary">{card.category}</Badge>}
              {card.rarity && <Badge variant="outline">{card.rarity}</Badge>}
              {card.regulationMark && (
                <Badge variant="outline">Mark {card.regulationMark}</Badge>
              )}
              {card.types.map((t) => (
                <Badge key={t}>{t}</Badge>
              ))}
            </div>

            {/* Stats */}
            {(card.hp !== null ||
              card.retreat !== null ||
              card.evolvesFrom ||
              card.weakness ||
              card.resistance) && (
              <div className="grid grid-cols-2 gap-x-8 gap-y-3 text-sm">
                {card.hp !== null && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      HP
                    </p>
                    <p className="font-semibold">{card.hp}</p>
                  </div>
                )}
                {card.retreat !== null && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Retreat
                    </p>
                    <p className="font-semibold">{card.retreat}</p>
                  </div>
                )}
                {card.evolvesFrom && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Evolves From
                    </p>
                    <p className="font-semibold">{card.evolvesFrom}</p>
                  </div>
                )}
                {card.weakness && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Weakness
                    </p>
                    <p className="font-semibold">
                      {card.weakness.type} {card.weakness.amount}
                    </p>
                  </div>
                )}
                {card.resistance && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Resistance
                    </p>
                    <p className="font-semibold">
                      {card.resistance.type} {card.resistance.amount}
                    </p>
                  </div>
                )}
              </div>
            )}

            {/* Attacks */}
            {card.attacks.length > 0 && (
              <div>
                <h2 className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide mb-2">
                  Attacks
                </h2>
                <div className="space-y-2">
                  {card.attacks.map((attack) => (
                    <div
                      key={attack.slot}
                      className="rounded-lg border bg-card p-3 space-y-1"
                    >
                      <div className="flex items-center justify-between">
                        <p className="font-semibold text-sm">{attack.name}</p>
                        {attack.damage && (
                          <p className="font-bold text-base tabular-nums">{attack.damage}</p>
                        )}
                      </div>
                      {attack.cost && (
                        <p className="text-xs text-muted-foreground">Cost: {attack.cost}</p>
                      )}
                      {attack.text && (
                        <p className="text-sm text-muted-foreground leading-relaxed">
                          {attack.text}
                        </p>
                      )}
                    </div>
                  ))}
                </div>
              </div>
            )}

            {/* Other prints */}
            {card.otherPrints.length > 0 && (
              <div>
                <h2 className="text-[10px] font-semibold text-muted-foreground uppercase tracking-wide mb-2">
                  Other Prints
                </h2>
                <div className="flex gap-2 overflow-x-auto pb-2">
                  {card.otherPrints.map((p) => (
                    <Link key={p.id} to={`/cards/${p.id}`} className="flex-none">
                      {p.thumb ? (
                        <img
                          src={p.thumb}
                          alt=""
                          className="h-20 rounded-lg border hover:border-primary/40 transition-colors"
                        />
                      ) : (
                        <div className="h-20 w-14 rounded-lg border bg-muted flex items-center justify-center">
                          <span className="text-[10px] text-muted-foreground">{p.id.slice(-4)}</span>
                        </div>
                      )}
                    </Link>
                  ))}
                </div>
              </div>
            )}
          </div>
        </div>
      </main>
    </div>
  )
}
