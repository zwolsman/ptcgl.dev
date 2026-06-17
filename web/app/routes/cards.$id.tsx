import * as React from "react"
import { Link } from "react-router"
import { fetchCard, type Card } from "~/api/client.server"
import { getLocale } from "~/lib/locale"
import { Badge } from "~/components/ui/badge"
import { PageHeader } from "~/components/page-header"
import type { LoaderFunctionArgs } from "react-router"

const ENERGY_LETTER_MAP: Record<string, string> = {
  G: "grass", R: "fire", W: "water", L: "lightning", P: "psychic",
  F: "fighting", D: "darkness", M: "metal", Y: "fairy", N: "dragon", C: "colorless",
}

const ENERGY_SPRITES: Record<string, string> = {
  fire: "/sprites/energy-black/fire.png",
  grass: "/sprites/energy-black/grass.png",
  water: "/sprites/energy-black/water.png",
  lightning: "/sprites/energy-black/lightning.png",
  psychic: "/sprites/energy-black/psychic.png",
  fighting: "/sprites/energy-black/fighting.png",
  darkness: "/sprites/energy-black/darkness.png",
  metal: "/sprites/energy-black/metal.png",
  dragon: "/sprites/energy-black/dragon.png",
  fairy: "/sprites/energy-black/fairy.png",
  colorless: "/sprites/energy-black/colorless.png",
}

const BADGE_SPRITES: Record<string, string> = {
  ex_atk: "/sprites/badges/ex.png",
  ex_lower_atk: "/sprites/badges/ex.png",
  ex_lower: "/sprites/badges/ex.png",
  vstar_atk: "/sprites/badges/vstar.png",
  vStar: "/sprites/badges/vstar.png",
  acespec: "/sprites/badges/ace-spec.png",
}

// Matches <sprite name="..."> tags or {ex}/{VSTAR}/{ACE SPEC} placeholders
const TOKEN_RE = /<sprite name="([^"]+)"[^>]*>|\{(ACE SPEC|VSTAR|ex)\}/g

const PLACEHOLDER_SPRITES: Record<string, string> = {
  ex: "/sprites/badges/ex.png",
  VSTAR: "/sprites/badges/vstar.png",
  "ACE SPEC": "/sprites/badges/ace-spec.png",
}

function SpriteImg({ src, alt }: { src: string; alt: string }) {
  return (
    <img
      src={src}
      alt={alt}
      className="inline-block align-middle mx-0.5"
      style={{ height: "1.1em", verticalAlign: "middle" }}
    />
  )
}

function EnergyCost({ cost }: { cost: string }) {
  return (
    <span className="flex items-center gap-0.5 shrink-0">
      {cost.split("").map((letter, i) => {
        const name = ENERGY_LETTER_MAP[letter]
        return name ? (
          <img key={i} src={`/sprites/energy/${name}.png`} alt={name} style={{ height: "1em" }} />
        ) : (
          <span key={i} className="text-xs text-muted-foreground font-mono">{letter}</span>
        )
      })}
    </span>
  )
}

function CardName({ name }: { name: string }) {
  // Replace {G}, {R}, etc. in energy card names with inline icons
  const parts = name.split(/\{([A-Z])\}/g)
  if (parts.length === 1) return <>{name}</>
  return (
    <>
      {parts.map((part, i) =>
        i % 2 === 0 ? (
          part
        ) : (
          <img
            key={i}
            src={`/sprites/energy/${ENERGY_LETTER_MAP[part] ?? "colorless"}.png`}
            alt={part}
            className="inline-block align-middle mx-0.5"
            style={{ height: "0.85em" }}
          />
        ),
      )}
    </>
  )
}

function AttackText({ text }: { text: string }) {
  const parts: React.ReactNode[] = []
  let last = 0
  let match: RegExpExecArray | null

  TOKEN_RE.lastIndex = 0
  while ((match = TOKEN_RE.exec(text)) !== null) {
    if (match.index > last) {
      parts.push(<span key={last} dangerouslySetInnerHTML={{ __html: text.slice(last, match.index) }} />)
    }

    const spriteName = match[1] // from <sprite name="...">
    const placeholder = match[2] // from {PLACEHOLDER}
    const key = match.index

    if (spriteName) {
      const src = ENERGY_SPRITES[spriteName] ?? BADGE_SPRITES[spriteName]
      if (src) {
        parts.push(<SpriteImg key={key} src={src} alt={spriteName} />)
      } else {
        parts.push(
          <span
            key={key}
            className="inline-flex items-center px-1 py-px rounded text-[10px] font-medium bg-secondary text-secondary-foreground mx-0.5 align-middle"
          >
            {spriteName}
          </span>,
        )
      }
    } else if (placeholder) {
      const src = PLACEHOLDER_SPRITES[placeholder]
      if (src) {
        parts.push(<SpriteImg key={key} src={src} alt={placeholder} />)
      }
    }

    last = match.index + match[0].length
  }

  if (last < text.length) {
    parts.push(<span key={last} dangerouslySetInnerHTML={{ __html: text.slice(last) }} />)
  }

  return <>{parts}</>
}

export function meta({ data }: { data: { locale: string; card: Card } | undefined }) {
  return [{ title: `${data?.card?.name ?? "Card"} | ptcgl.dev` }]
}

export async function loader({ request, params }: LoaderFunctionArgs) {
  const locale = getLocale(request)
  const card = await fetchCard(params.id!, locale)
  return { locale, card }
}

export default function CardDetail({
  loaderData,
}: {
  loaderData: Awaited<ReturnType<typeof loader>>
}) {
  const { locale, card } = loaderData
  const image = card.assets.hires ?? card.assets.thumb

  const [overlayOpen, setOverlayOpen] = React.useState(false)
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

  return (
    <div className="min-h-screen bg-background">
      <PageHeader locale={locale}>
        <Link
          to={`/sets/${card.setId}`}
          className="text-sm text-muted-foreground hover:text-foreground transition-colors"
        >
          ← Back to Set
        </Link>
        <span className="text-muted-foreground text-sm">/</span>
        <span className="text-sm font-medium truncate">{card.name ?? card.id}</span>
      </PageHeader>
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
              <h1 className="text-2xl font-bold text-foreground">
                {card.name ? <CardName name={card.name} /> : card.id}
              </h1>
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
            {(card.hp != null ||
              card.evolvesFrom ||
              card.weakness ||
              card.resistance) && (
              <div className="grid grid-cols-2 gap-x-8 gap-y-3 text-sm">
                {card.hp != null && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      HP
                    </p>
                    <p className="font-semibold">{card.hp}</p>
                  </div>
                )}
                {card.hp != null && card.retreat != null && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Retreat
                    </p>
                    <span className="flex items-center gap-0.5 mt-0.5">
                      {card.retreat === 0 ? (
                        <span className="font-semibold text-sm">Free</span>
                      ) : (
                        Array.from({ length: card.retreat }).map((_, i) => (
                          <img key={i} src="/sprites/energy/colorless.png" alt="colorless" style={{ height: "1.1em" }} />
                        ))
                      )}
                    </span>
                  </div>
                )}
                {card.evolvesFrom && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Evolves From
                    </p>
                    <Link
                      to={`/search?name=${encodeURIComponent(card.evolvesFrom)}&exact=true`}
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
                    <span className="flex items-center gap-1 mt-0.5 font-semibold text-sm">
                      {ENERGY_LETTER_MAP[card.weakness.type] && (
                        <img
                          src={`/sprites/energy/${ENERGY_LETTER_MAP[card.weakness.type]}.png`}
                          alt={card.weakness.type}
                          style={{ height: "1.1em" }}
                        />
                      )}
                      {card.weakness.amount
                        ? (card.weakness.amount.includes("-") ? card.weakness.amount : `×${card.weakness.amount}`)
                        : "×2"}
                    </span>
                  </div>
                )}
                {card.resistance && (
                  <div>
                    <p className="text-[10px] font-medium text-muted-foreground uppercase tracking-wide">
                      Resistance
                    </p>
                    <span className="flex items-center gap-1 mt-0.5 font-semibold text-sm">
                      {ENERGY_LETTER_MAP[card.resistance.type] && (
                        <img
                          src={`/sprites/energy/${ENERGY_LETTER_MAP[card.resistance.type]}.png`}
                          alt={card.resistance.type}
                          style={{ height: "1.1em" }}
                        />
                      )}
                      {card.resistance.amount}
                    </span>
                  </div>
                )}
              </div>
            )}

            {/* Card body text (trainer / energy / ability text) */}
            {card.text && (
              <div className="rounded-lg border bg-card p-3 space-y-1.5">
                {card.text.split("\n").filter(Boolean).map((line, i) => (
                  <p key={i} className="text-sm text-muted-foreground leading-relaxed">
                    <AttackText text={line} />
                  </p>
                ))}
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
                          {attack.cost && <EnergyCost cost={attack.cost} />}
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
            src={card.assets.hires ?? card.assets.thumb ?? ""}
            alt={card.name ?? card.id}
            className="max-h-[88vh] w-auto object-cover cursor-default"
            style={{
              aspectRatio: "0.718",
              borderRadius: "4.55% / 3.5%",
              boxShadow: "0 30px 80px rgba(0,0,0,0.6)",
            }}
            onClick={(e) => e.stopPropagation()}
          />
        </div>
      )}
    </div>
  )
}
