import * as React from "react"
import { Link } from "react-router"
import { fetchCard, type Card } from "~/api/client"
import { Badge } from "~/components/ui/badge"
import type { LoaderFunctionArgs } from "react-router"

const SPRITE_RE = /<sprite name="([^"]+)"[^>]*>/g

function AttackText({ text }: { text: string }) {
  const parts: React.ReactNode[] = []
  let last = 0
  let match: RegExpExecArray | null

  SPRITE_RE.lastIndex = 0
  while ((match = SPRITE_RE.exec(text)) !== null) {
    if (match.index > last) {
      const chunk = text.slice(last, match.index)
      parts.push(<span key={last} dangerouslySetInnerHTML={{ __html: chunk }} />)
    }
    const name = match[1]
    parts.push(
      <span
        key={match.index}
        className="inline-flex items-center px-1 py-px rounded text-[10px] font-medium bg-secondary text-secondary-foreground mx-0.5 align-middle"
      >
        {name.charAt(0).toUpperCase() + name.slice(1).toLowerCase()}
      </span>,
    )
    last = match.index + match[0].length
  }

  if (last < text.length) {
    parts.push(<span key={last} dangerouslySetInnerHTML={{ __html: text.slice(last) }} />)
  }

  return <>{parts}</>
}

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

  const [overlayOpen, setOverlayOpen] = React.useState(false)
  const overlayCardRef = React.useRef<HTMLImageElement>(null)

  React.useEffect(() => {
    if (!overlayOpen) return
    const onKey = (e: KeyboardEvent) => { if (e.key === "Escape") setOverlayOpen(false) }
    document.addEventListener("keydown", onKey)
    document.body.style.overflow = "hidden"
    return () => {
      document.removeEventListener("keydown", onKey)
      document.body.style.overflow = ""
    }
  }, [overlayOpen])

  function onCardMouseMove(e: React.MouseEvent<HTMLImageElement>) {
    const el = overlayCardRef.current
    if (!el) return
    const rect = el.getBoundingClientRect()
    const x = (e.clientX - rect.left) / rect.width - 0.5
    const y = (e.clientY - rect.top) / rect.height - 0.5
    el.style.transition = "transform 0.08s ease"
    el.style.transform = `perspective(800px) rotateY(${x * 22}deg) rotateX(${-y * 16}deg)`
  }

  function onCardMouseLeave() {
    const el = overlayCardRef.current
    if (!el) return
    el.style.transition = "transform 0.6s ease"
    el.style.transform = "perspective(800px) rotateY(0deg) rotateX(0deg)"
  }

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
                className="w-full object-cover shadow-lg cursor-zoom-in"
                style={{ aspectRatio: "0.718", borderRadius: "4.55% / 3.5%" }}
                onClick={() => setOverlayOpen(true)}
              />
            ) : (
              <div
                className="w-full bg-muted flex items-center justify-center"
                style={{ aspectRatio: "0.718", borderRadius: "4.55% / 3.5%" }}
              >
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
                <Badge key={t}>{t.charAt(0).toUpperCase() + t.slice(1).toLowerCase()}</Badge>
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
                    <Link
                      to={`/search?name=${encodeURIComponent(card.evolvesFrom)}`}
                      className="font-semibold hover:underline text-primary"
                    >
                      {card.evolvesFrom}
                    </Link>
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
                      className="rounded-lg border bg-card p-3 space-y-1.5"
                    >
                      <div className="flex items-center justify-between gap-2">
                        <div className="flex items-center gap-2 min-w-0">
                          {attack.cost && (
                            <span className="text-xs text-muted-foreground font-mono shrink-0">
                              {attack.cost}
                            </span>
                          )}
                          <p className="font-semibold text-sm truncate">
                            {attack.name ?? `Attack ${attack.slot}`}
                          </p>
                        </div>
                        {attack.damage && (
                          <p className="font-bold text-base tabular-nums shrink-0">
                            {attack.damage}
                          </p>
                        )}
                      </div>
                      {attack.text && (
                        <p className="text-sm text-muted-foreground leading-relaxed">
                          <AttackText text={attack.text} />
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
                    <Link key={p.id} to={`/cards/${p.id}`} className="flex-none w-14">
                      {p.thumb ? (
                        <img
                          src={p.thumb}
                          alt=""
                          className="w-full object-cover border hover:border-primary/40 transition-colors"
                          style={{ aspectRatio: "0.718", borderRadius: "4.55% / 3.5%" }}
                        />
                      ) : (
                        <div
                          className="w-full border bg-muted flex items-center justify-center"
                          style={{ aspectRatio: "0.718", borderRadius: "4.55% / 3.5%" }}
                        >
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

      {overlayOpen && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center cursor-zoom-out"
          style={{ animation: "overlay-fade-in 0.25s ease forwards", backgroundColor: "rgba(0,0,0,0.85)" }}
          onClick={() => setOverlayOpen(false)}
        >
          <img
            ref={overlayCardRef}
            src={card.assets.hires ?? card.assets.thumb ?? ""}
            alt={card.name ?? card.id}
            className="max-h-[88vh] w-auto cursor-default"
            style={{
              aspectRatio: "0.718",
              borderRadius: "4.55% / 3.5%",
              boxShadow: "0 30px 80px rgba(0,0,0,0.6)",
              animation: "card-swirl-in 0.7s ease-out forwards",
              willChange: "transform",
              transformStyle: "preserve-3d",
            }}
            onClick={(e) => e.stopPropagation()}
            onMouseMove={onCardMouseMove}
            onMouseLeave={onCardMouseLeave}
          />
        </div>
      )}
    </div>
  )
}
